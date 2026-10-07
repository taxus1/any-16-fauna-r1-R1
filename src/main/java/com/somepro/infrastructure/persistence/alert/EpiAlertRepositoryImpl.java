package com.somepro.infrastructure.persistence.alert;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.somepro.domain.alert.model.EpiAlert;
import com.somepro.domain.alert.repository.EpiAlertRepository;
import com.somepro.domain.report.model.AbnormalReport;
import com.somepro.domain.shared.model.PageResult;
import com.somepro.infrastructure.persistence.alert.converter.EpiAlertPoConverter;
import com.somepro.infrastructure.persistence.alert.po.EpiAlertPO;
import com.somepro.infrastructure.persistence.report.AbnormalReportMapper;
import com.somepro.infrastructure.persistence.report.po.AbnormalReportPO;
import com.somepro.infrastructure.persistence.support.BlockingJdbc;
import com.somepro.infrastructure.persistence.support.Conditions;
import com.somepro.infrastructure.persistence.support.PagingQuery;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import reactor.core.publisher.Mono;

/**
 * 疫病预警与处置仓储适配器（基础设施层）。
 *
 * 只负责预警立起来以后的处置推进、查看与翻页（阻塞 JDBC 走 {@link BlockingJdbc}、
 * 分页走 {@link PagingQuery}）；预警的「立」在样本检测回填的同一事务里，见
 * {@link com.somepro.infrastructure.persistence.sample.SampleTestRepositoryImpl}。
 *
 * 处置推进是「两头一起动」：一个事务里先按原状态条件更新预警（同一条预警的状态
 * 只翻得动一次，并发/重复推进在这被拦），推进到已解除时再把挂的那条上报条件更新
 * 到已结案 —— 还没结案的跟着收尾，已经结案的 0 行照旧，不动第二下。
 * 两头动作各是一个小方法（{@link #flipStatus} / {@link #closeLinkedReport}），
 * 事务编排只负责把它们串在一个事务里。
 */
@Repository
public class EpiAlertRepositoryImpl implements EpiAlertRepository {

    private final EpiAlertMapper alertMapper;
    private final AbnormalReportMapper reportMapper;
    private final TransactionTemplate transactionTemplate;

    public EpiAlertRepositoryImpl(EpiAlertMapper alertMapper,
                                  AbnormalReportMapper reportMapper,
                                  PlatformTransactionManager transactionManager) {
        this.alertMapper = alertMapper;
        this.reportMapper = reportMapper;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Override
    public Mono<EpiAlert> findById(Long id) {
        return BlockingJdbc.blocking(() -> {
            EpiAlertPO po = alertMapper.selectById(id);
            return po == null ? null : EpiAlertPoConverter.toDomain(po);
        });
    }

    @Override
    public Mono<Boolean> advance(EpiAlert alert, String fromStatus) {
        return BlockingJdbc.blocking(() -> transactionTemplate.execute(txStatus -> {
            if (!flipStatus(alert, fromStatus)) {
                return false;
            }
            // 上报侧：解除这一步顺手把挂的那条上报收尾 —— 还没结案的跟着推到已结案，
            // 已经结案的（0 行）照旧；与预警推进同一个事务落。
            if (EpiAlert.STATUS_RESOLVED.equals(alert.getStatus())) {
                closeLinkedReport(alert.getReportId());
            }
            return true;
        }));
    }

    @Override
    public Mono<PageResult<EpiAlert>> page(int pageNum, int pageSize,
                                           Long reportId, Long sampleId,
                                           String alertLevel, String status) {
        return BlockingJdbc.blocking(() -> PagingQuery.page(pageNum, pageSize,
                () -> alertMapper.selectList(Wrappers.<EpiAlertPO>lambdaQuery()
                        .eq(reportId != null, EpiAlertPO::getReportId, reportId)
                        .eq(sampleId != null, EpiAlertPO::getSampleId, sampleId)
                        .eq(Conditions.hasText(alertLevel), EpiAlertPO::getAlertLevel, alertLevel)
                        .eq(Conditions.hasText(status), EpiAlertPO::getStatus, status)
                        .orderByAsc(EpiAlertPO::getId)),
                EpiAlertPoConverter::toDomain));
    }

    /**
     * 预警侧：按原状态条件更新，同一条预警的状态只翻得动一次（并发推同一条只放行一下）。
     * SET 只带新状态/处置措施/解除时刻（非空策略），每推一步的时刻由审计列 update_time 记下。
     */
    private boolean flipStatus(EpiAlert alert, String fromStatus) {
        EpiAlertPO alertPo = new EpiAlertPO();
        alertPo.setStatus(alert.getStatus());
        alertPo.setDisposalMethod(alert.getDisposalMethod());
        alertPo.setResolvedAt(alert.getResolvedAt());
        int rows = alertMapper.update(alertPo, Wrappers.<EpiAlertPO>lambdaUpdate()
                .eq(EpiAlertPO::getId, alert.getId())
                .eq(EpiAlertPO::getStatus, fromStatus));
        return rows == 1;
    }

    /** 上报侧：把挂的那条上报条件更新到已结案；已经结案的 0 行照旧，不动第二下。 */
    private void closeLinkedReport(Long reportId) {
        AbnormalReportPO reportPo = new AbnormalReportPO();
        reportPo.setStatus(AbnormalReport.STATUS_CLOSED);
        reportMapper.update(reportPo, Wrappers.<AbnormalReportPO>lambdaUpdate()
                .eq(AbnormalReportPO::getId, reportId)
                .ne(AbnormalReportPO::getStatus, AbnormalReport.STATUS_CLOSED));
    }
}
