package com.somepro.infrastructure.persistence.obs;

import cn.hutool.core.util.IdUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.somepro.domain.obs.model.ObsSummary;
import com.somepro.domain.obs.model.WildlifeObs;
import com.somepro.domain.obs.repository.WildlifeObsRepository;
import com.somepro.domain.shared.model.PageResult;
import com.somepro.infrastructure.persistence.obs.converter.WildlifeObsPoConverter;
import com.somepro.infrastructure.persistence.obs.po.WildlifeObsPO;
import com.somepro.infrastructure.persistence.support.BizNoGenerator;
import com.somepro.infrastructure.persistence.support.BlockingJdbc;
import com.somepro.infrastructure.persistence.support.Conditions;
import com.somepro.infrastructure.persistence.support.PagingQuery;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 野生动物观测记录仓储适配器（基础设施层）。
 *
 * 只负责观测本身的存取：编号分配、改录、作废、分页与任务观测账汇总。
 * 阻塞 JDBC 统一走 {@link BlockingJdbc} 桥接、分页统一走 {@link PagingQuery}。
 *
 * 编号分配：obsNo 按 WO-YYYY-NNNNNN 生成（年份按登记当下，序号 6 位零填充），
 * 并发撞号由 {@link BizNoGenerator} 重试，唯一索引兜底，一个号只落一条。
 * 作废 = @TableLogic 逻辑删除（del_flag=1）：清单翻不到，底子留在表里备查；
 * 作废记录占用的编号不复用（selectMaxSeq 的自定义 @Select 不拼 del_flag）。
 *
 * 数账口径：selectCount 走 @TableLogic 自动拼 del_flag=0，与观测录入可见的记录同一套过滤；
 * 异常按 {@link ObsSummary#ABNORMAL_HEALTH}（受伤/死亡/疑似疫病）数。
 */
@Repository
public class WildlifeObsRepositoryImpl implements WildlifeObsRepository {

    /** 编号前缀：WO-（完整形如 WO-2026-） */
    private static final String NO_PREFIX = "WO-";

    /** 观测编号序号位数：6 位零填充（WO-2026-000001），超出位数自然扩展，不截断 */
    private static final int SEQ_WIDTH = 6;

    private final WildlifeObsMapper obsMapper;

    public WildlifeObsRepositoryImpl(WildlifeObsMapper obsMapper) {
        this.obsMapper = obsMapper;
    }

    @Override
    public Mono<WildlifeObs> create(WildlifeObs obs) {
        return BlockingJdbc.blocking(() -> {
            String prefix = NO_PREFIX + LocalDate.now().getYear() + "-";
            return BizNoGenerator.insertWithRetry(
                    () -> obsMapper.selectMaxSeq(prefix, prefix.length() + 1),
                    prefix,
                    no -> doInsert(obs, no),
                    SEQ_WIDTH);
        });
    }

    @Override
    public Mono<WildlifeObs> update(WildlifeObs obs) {
        return BlockingJdbc.blocking(() -> {
            WildlifeObsPO po = WildlifeObsPoConverter.toPo(obs);
            obsMapper.updateById(po);
            return WildlifeObsPoConverter.toDomain(po);
        });
    }

    @Override
    public Mono<WildlifeObs> findById(Long id) {
        return BlockingJdbc.blocking(() -> {
            WildlifeObsPO po = obsMapper.selectById(id);
            return po == null ? null : WildlifeObsPoConverter.toDomain(po);
        });
    }

    @Override
    public Mono<Void> voidObs(Long id) {
        return BlockingJdbc.blocking(() -> {
            // @TableLogic 把 deleteById 改写成 UPDATE t_wildlife_obs SET del_flag=1
            // WHERE id=? AND del_flag=0：已作废的重复作废不动第二下，底子仍留在表里。
            obsMapper.deleteById(id);
            return Boolean.TRUE;
        }).then();
    }

    @Override
    public Mono<PageResult<WildlifeObs>> page(int pageNum, int pageSize,
                                              Long taskId, Long siteId, String speciesCode,
                                              String healthStatus,
                                              LocalDateTime observedFrom, LocalDateTime observedTo) {
        return BlockingJdbc.blocking(() -> PagingQuery.page(pageNum, pageSize,
                () -> obsMapper.selectList(Wrappers.<WildlifeObsPO>lambdaQuery()
                        .eq(taskId != null, WildlifeObsPO::getTaskId, taskId)
                        .eq(siteId != null, WildlifeObsPO::getSiteId, siteId)
                        .eq(Conditions.hasText(speciesCode), WildlifeObsPO::getSpeciesCode, speciesCode)
                        .eq(Conditions.hasText(healthStatus), WildlifeObsPO::getHealthStatus, healthStatus)
                        .ge(observedFrom != null, WildlifeObsPO::getObservedAt, observedFrom)
                        .lt(observedTo != null, WildlifeObsPO::getObservedAt, observedTo)
                        .orderByAsc(WildlifeObsPO::getId)),
                WildlifeObsPoConverter::toDomain));
    }

    @Override
    public Mono<ObsSummary> summarizeByTaskId(Long taskId) {
        return BlockingJdbc.blocking(() -> {
            long obsCount = obsMapper.selectCount(Wrappers.<WildlifeObsPO>lambdaQuery()
                    .eq(WildlifeObsPO::getTaskId, taskId));
            long abnormalCount = obsMapper.selectCount(Wrappers.<WildlifeObsPO>lambdaQuery()
                    .eq(WildlifeObsPO::getTaskId, taskId)
                    .in(WildlifeObsPO::getHealthStatus, ObsSummary.ABNORMAL_HEALTH));
            return new ObsSummary(Math.toIntExact(obsCount), Math.toIntExact(abnormalCount));
        });
    }

    private WildlifeObs doInsert(WildlifeObs obs, String obsNo) {
        obs.setObsNo(obsNo);
        WildlifeObsPO po = WildlifeObsPoConverter.toPo(obs);
        po.setId(IdUtil.getSnowflakeNextId());
        obsMapper.insert(po);
        return WildlifeObsPoConverter.toDomain(po);
    }
}
