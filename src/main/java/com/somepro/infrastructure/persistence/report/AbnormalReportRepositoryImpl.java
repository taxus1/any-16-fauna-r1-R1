package com.somepro.infrastructure.persistence.report;

import cn.hutool.core.util.IdUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.somepro.common.exception.BizException;
import com.somepro.domain.report.model.AbnormalReport;
import com.somepro.domain.report.repository.AbnormalReportRepository;
import com.somepro.domain.shared.model.PageResult;
import com.somepro.infrastructure.persistence.obs.WildlifeObsMapper;
import com.somepro.infrastructure.persistence.report.converter.AbnormalReportPoConverter;
import com.somepro.infrastructure.persistence.report.po.AbnormalReportPO;
import com.somepro.infrastructure.persistence.support.BizNoGenerator;
import com.somepro.infrastructure.persistence.support.BlockingJdbc;
import com.somepro.infrastructure.persistence.support.PageQueries;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import reactor.core.publisher.Mono;

import java.time.LocalDate;

import static com.somepro.infrastructure.persistence.support.PageQueries.hasText;

/**
 * 异常个体上报仓储适配器（基础设施层）。
 *
 * 编号分配：reportNo 按 AR-YYYY-NNNN 生成（年份按登记当下，序号 4 位零填充），
 * 并发撞号由 {@link BizNoGenerator} 重试，唯一索引兜底，一个号只落一条；
 * 作废记录占用的编号不复用（selectMaxSeq 的自定义 @Select 不拼 del_flag）。
 * 作废 = @TableLogic 逻辑删除（del_flag=1）：名单翻不到，账留在表里。
 *
 * 「同一观测只挂一条未作废上报」在登记事务内兜底（见 {@link #create}）：
 * 先 SELECT ... FOR UPDATE 锁住来源观测那一行，再数该观测名下未作废上报、落库 ——
 * 两人前后脚一起递，后到者在行锁上排队，等前面那单提交后数到已有一条，报业务失败；
 * 原来那条作废了（del_flag=1 不计数）才放行重报。
 */
@Repository
public class AbnormalReportRepositoryImpl implements AbnormalReportRepository {

    /** 编号前缀：AR-（完整形如 AR-2026-） */
    private static final String NO_PREFIX = "AR-";

    private final AbnormalReportMapper reportMapper;
    private final WildlifeObsMapper obsMapper;
    private final TransactionTemplate transactionTemplate;

    public AbnormalReportRepositoryImpl(AbnormalReportMapper reportMapper,
                                        WildlifeObsMapper obsMapper,
                                        PlatformTransactionManager transactionManager) {
        this.reportMapper = reportMapper;
        this.obsMapper = obsMapper;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Override
    public Mono<AbnormalReport> create(AbnormalReport report) {
        return BlockingJdbc.blocking(() -> transactionTemplate.execute(txStatus -> {
            lockSourceObservation(report.getObsId());
            rejectIfActiveReportExists(report.getObsId());
            return insertWithGeneratedNo(report);
        }));
    }

    @Override
    public Mono<AbnormalReport> findById(Long id) {
        return BlockingJdbc.blocking(() -> {
            AbnormalReportPO po = reportMapper.selectById(id);
            return po == null ? null : AbnormalReportPoConverter.toDomain(po);
        });
    }

    @Override
    public Mono<Boolean> advance(AbnormalReport report, String fromStatus) {
        return BlockingJdbc.blocking(() -> {
            // 条件更新：只有仍处原状态的那一行才翻得动（并发推同一单只放行一下）；
            // SET 只带新状态，处置时刻由审计列 update_time 自动记下，del_flag=0 由 @TableLogic 拼上。
            AbnormalReportPO po = new AbnormalReportPO();
            po.setStatus(report.getStatus());
            int rows = reportMapper.update(po, Wrappers.<AbnormalReportPO>lambdaUpdate()
                    .eq(AbnormalReportPO::getId, report.getId())
                    .eq(AbnormalReportPO::getStatus, fromStatus));
            return rows == 1;
        });
    }

    @Override
    public Mono<Void> voidReport(Long id) {
        return BlockingJdbc.blocking(() -> {
            // @TableLogic 把 deleteById 改写成 UPDATE t_abnormal_report SET del_flag=1
            // WHERE id=? AND del_flag=0：已作废的重复作废不动第二下，账仍留在表里。
            reportMapper.deleteById(id);
            return Boolean.TRUE;
        }).then();
    }

    @Override
    public Mono<PageResult<AbnormalReport>> page(int pageNum, int pageSize,
                                                 Long siteId, String category,
                                                 String severity, String status) {
        return PageQueries.page(pageNum, pageSize,
                () -> reportMapper.selectList(Wrappers.<AbnormalReportPO>lambdaQuery()
                        .eq(siteId != null, AbnormalReportPO::getSiteId, siteId)
                        .eq(hasText(category), AbnormalReportPO::getCategory, category)
                        .eq(hasText(severity), AbnormalReportPO::getSeverity, severity)
                        .eq(hasText(status), AbnormalReportPO::getStatus, status)
                        .orderByAsc(AbnormalReportPO::getId)),
                AbnormalReportPoConverter::toDomain);
    }

    /** 锁住来源观测那一行：同一观测的并发上报在这里排队，锁随事务提交/回滚释放。 */
    private void lockSourceObservation(Long obsId) {
        obsMapper.lockById(obsId);
    }

    /** 数在册上报（@TableLogic 自动拼 del_flag=0）：已有一条未作废的就不许再落。 */
    private void rejectIfActiveReportExists(Long obsId) {
        Long active = reportMapper.selectCount(Wrappers.<AbnormalReportPO>lambdaQuery()
                .eq(AbnormalReportPO::getObsId, obsId));
        if (active != null && active > 0) {
            throw new BizException("该观测已有一条未作废的上报，不能重复上报；原上报作废后才能重报");
        }
    }

    private AbnormalReport insertWithGeneratedNo(AbnormalReport report) {
        String prefix = NO_PREFIX + LocalDate.now().getYear() + "-";
        return BizNoGenerator.insertWithRetry(
                () -> reportMapper.selectMaxSeq(prefix, prefix.length() + 1),
                prefix,
                no -> doInsert(report, no));
    }

    private AbnormalReport doInsert(AbnormalReport report, String reportNo) {
        report.setReportNo(reportNo);
        AbnormalReportPO po = AbnormalReportPoConverter.toPo(report);
        po.setId(IdUtil.getSnowflakeNextId());
        reportMapper.insert(po);
        return AbnormalReportPoConverter.toDomain(po);
    }
}
