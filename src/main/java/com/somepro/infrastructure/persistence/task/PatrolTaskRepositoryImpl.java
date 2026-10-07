package com.somepro.infrastructure.persistence.task;

import cn.hutool.core.util.IdUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.somepro.common.exception.BizException;
import com.somepro.domain.shared.model.PageResult;
import com.somepro.domain.task.model.PatrolTask;
import com.somepro.domain.task.repository.PatrolTaskRepository;
import com.somepro.infrastructure.persistence.support.BizNoGenerator;
import com.somepro.infrastructure.persistence.support.BlockingJdbc;
import com.somepro.infrastructure.persistence.support.Conditions;
import com.somepro.infrastructure.persistence.support.PagingQuery;
import com.somepro.infrastructure.persistence.task.converter.PatrolTaskPoConverter;
import com.somepro.infrastructure.persistence.task.po.PatrolTaskPO;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;

import java.time.LocalDate;

/**
 * 巡护任务仓储适配器（基础设施层）。
 *
 * 只负责任务本身的存取：编号分配、条件更新（开工/完成）、取消销账、分页与占位计数。
 * 阻塞 JDBC 统一走 {@link BlockingJdbc} 桥接、分页统一走 {@link PagingQuery}。
 *
 * 编号分配：taskNo 为空时按 PT-YYYY-NNNN 生成（并发撞号由 {@link BizNoGenerator} 重试）；
 * 指定编号时撞唯一索引转业务异常，不甩底层 DuplicateKeyException。
 * 取消 = 状态置 CANCELLED + 逻辑删除（del_flag=1）：名单翻不到，账留在表里。
 */
@Repository
public class PatrolTaskRepositoryImpl implements PatrolTaskRepository {

    /** 编号前缀：PT-年-（如 PT-2026-） */
    private static final String NO_PREFIX = "PT-";

    private final PatrolTaskMapper taskMapper;

    public PatrolTaskRepositoryImpl(PatrolTaskMapper taskMapper) {
        this.taskMapper = taskMapper;
    }

    @Override
    public Mono<PatrolTask> create(PatrolTask task) {
        return BlockingJdbc.blocking(() -> {
            if (task.getTaskNo() != null && !task.getTaskNo().isBlank()) {
                try {
                    return doInsert(task, task.getTaskNo().trim());
                } catch (DuplicateKeyException e) {
                    throw new BizException("巡护任务编号已存在：" + task.getTaskNo());
                }
            }
            String prefix = NO_PREFIX + LocalDate.now().getYear() + "-";
            return BizNoGenerator.insertWithRetry(
                    () -> taskMapper.selectMaxSeq(prefix, prefix.length() + 1),
                    prefix,
                    no -> doInsert(task, no));
        });
    }

    @Override
    public Mono<PatrolTask> update(PatrolTask task) {
        return BlockingJdbc.blocking(() -> {
            PatrolTaskPO po = PatrolTaskPoConverter.toPo(task);
            taskMapper.updateById(po);
            return PatrolTaskPoConverter.toDomain(po);
        });
    }

    @Override
    public Mono<Void> cancel(PatrolTask task) {
        return BlockingJdbc.blocking(() -> {
            // 先落状态 CANCELLED（审计字段自动填充），再 @TableLogic 置 del_flag=1：
            // UPDATE t_patrol_task SET del_flag = 1 WHERE id = ? AND del_flag = 0
            PatrolTaskPO po = PatrolTaskPoConverter.toPo(task);
            taskMapper.updateById(po);
            taskMapper.deleteById(task.getId());
            return Boolean.TRUE;
        }).then();
    }

    @Override
    public Mono<Boolean> start(PatrolTask task) {
        return BlockingJdbc.blocking(() -> {
            // 条件更新：只有仍处 PENDING 的那一行才翻得动（并发点两下只放行一下）；
            // SET 只带状态与开工时刻，审计字段由 MetaObjectHandler 填充，del_flag=0 由 @TableLogic 拼上。
            PatrolTaskPO po = new PatrolTaskPO();
            po.setStatus(task.getStatus());
            po.setStartedAt(task.getStartedAt());
            int rows = taskMapper.update(po, Wrappers.<PatrolTaskPO>lambdaUpdate()
                    .eq(PatrolTaskPO::getId, task.getId())
                    .eq(PatrolTaskPO::getStatus, PatrolTask.STATUS_PENDING));
            return rows == 1;
        });
    }

    @Override
    public Mono<Boolean> complete(PatrolTask task) {
        return BlockingJdbc.blocking(() -> {
            // 条件更新：只有仍处 IN_PROGRESS 的那一行才翻得动；
            // 完成时刻与观测账一笔写回，重复回报翻不动、不会二次计数。
            PatrolTaskPO po = new PatrolTaskPO();
            po.setStatus(task.getStatus());
            po.setFinishedAt(task.getFinishedAt());
            po.setObsCount(task.getObsCount());
            po.setAbnormalCount(task.getAbnormalCount());
            int rows = taskMapper.update(po, Wrappers.<PatrolTaskPO>lambdaUpdate()
                    .eq(PatrolTaskPO::getId, task.getId())
                    .eq(PatrolTaskPO::getStatus, PatrolTask.STATUS_IN_PROGRESS));
            return rows == 1;
        });
    }

    @Override
    public Mono<PatrolTask> findById(Long id) {
        return BlockingJdbc.blocking(() -> {
            PatrolTaskPO po = taskMapper.selectById(id);
            return po == null ? null : PatrolTaskPoConverter.toDomain(po);
        });
    }

    @Override
    public Mono<PageResult<PatrolTask>> page(int pageNum, int pageSize,
                                             Long stationId, Long siteId, String patrolType,
                                             String status, LocalDate plannedDate) {
        return BlockingJdbc.blocking(() -> PagingQuery.page(pageNum, pageSize,
                () -> taskMapper.selectList(Wrappers.<PatrolTaskPO>lambdaQuery()
                        .eq(stationId != null, PatrolTaskPO::getStationId, stationId)
                        .eq(siteId != null, PatrolTaskPO::getSiteId, siteId)
                        .eq(Conditions.hasText(patrolType), PatrolTaskPO::getPatrolType, patrolType)
                        .eq(Conditions.hasText(status), PatrolTaskPO::getStatus, status)
                        .eq(plannedDate != null, PatrolTaskPO::getPlannedDate, plannedDate)
                        .orderByAsc(PatrolTaskPO::getId)),
                PatrolTaskPoConverter::toDomain));
    }

    @Override
    public Mono<Long> countUnfinishedBySiteAndDate(Long siteId, LocalDate plannedDate, Long excludeId) {
        return BlockingJdbc.blocking(() -> taskMapper.selectCount(Wrappers.<PatrolTaskPO>lambdaQuery()
                .eq(PatrolTaskPO::getSiteId, siteId)
                .eq(PatrolTaskPO::getPlannedDate, plannedDate)
                .in(PatrolTaskPO::getStatus, PatrolTask.STATUS_PENDING, PatrolTask.STATUS_IN_PROGRESS)
                .ne(excludeId != null, PatrolTaskPO::getId, excludeId)));
    }

    private PatrolTask doInsert(PatrolTask task, String taskNo) {
        task.setTaskNo(taskNo);
        PatrolTaskPO po = PatrolTaskPoConverter.toPo(task);
        po.setId(IdUtil.getSnowflakeNextId());
        taskMapper.insert(po);
        return PatrolTaskPoConverter.toDomain(po);
    }
}
