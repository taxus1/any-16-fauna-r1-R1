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
import com.somepro.infrastructure.persistence.support.PageQueries;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import reactor.core.publisher.Mono;

import static com.somepro.infrastructure.persistence.support.PageQueries.hasText;

/**
 * 疫病预警与处置仓储适配器（基础设施层）。
 *
 * 预警的「立」不在这一侧：样本检测那条链上结果一录成阳性，预警就在样本仓储的
 * 同一个事务里跟着落库（见 PositiveSampleAlertRaiser），这里只管立起来以后的
 * 处置推进、查看与翻页。
 *
 * 处置推进是「两头一起动」，一个事务里按职责分两步（见 {@link #advance}）：
 * 1. 按原状态条件更新预警（同一条预警的状态只翻得动一次，并发/重复推进在这被拦）；
 * 2. 推进到已解除时把挂的那条上报条件更新到已结案 —— 还没结案的跟着收尾，
 *    已经结案的 0 行照旧，不动第二下。
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
            if (!flipAlert(alert, fromStatus)) {
                return false;
            }
            closeLinkedReportIfResolved(alert);
            return true;
        }));
    }

    @Override
    public Mono<PageResult<EpiAlert>> page(int pageNum, int pageSize,
                                           Long reportId, Long sampleId,
                                           String alertLevel, String status) {
        return PageQueries.page(pageNum, pageSize,
                () -> alertMapper.selectList(Wrappers.<EpiAlertPO>lambdaQuery()
                        .eq(reportId != null, EpiAlertPO::getReportId, reportId)
                        .eq(sampleId != null, EpiAlertPO::getSampleId, sampleId)
                        .eq(hasText(alertLevel), EpiAlertPO::getAlertLevel, alertLevel)
                        .eq(hasText(status), EpiAlertPO::getStatus, status)
                        .orderByAsc(EpiAlertPO::getId)),
                EpiAlertPoConverter::toDomain);
    }

    /**
     * 预警侧：按原状态条件更新，同一条预警的状态只翻得动一次（并发推同一条只放行一下）；
     * SET 只带新状态/处置措施/解除时刻（非空策略），每推一步的时刻由审计列 update_time 记下。
     */
    private boolean flipAlert(EpiAlert alert, String fromStatus) {
        EpiAlertPO alertPo = new EpiAlertPO();
        alertPo.setStatus(alert.getStatus());
        alertPo.setDisposalMethod(alert.getDisposalMethod());
        alertPo.setResolvedAt(alert.getResolvedAt());
        int rows = alertMapper.update(alertPo, Wrappers.<EpiAlertPO>lambdaUpdate()
                .eq(EpiAlertPO::getId, alert.getId())
                .eq(EpiAlertPO::getStatus, fromStatus));
        return rows == 1;
    }

    /**
     * 上报侧：解除这一步顺手把挂的那条上报收尾 —— 还没结案的跟着推到已结案，
     * 已经结案的（0 行）照旧；与预警推进同一个事务落。
     */
    private void closeLinkedReportIfResolved(EpiAlert alert) {
        if (!alert.resolved()) {
            return;
        }
        AbnormalReportPO reportPo = new AbnormalReportPO();
        reportPo.setStatus(AbnormalReport.STATUS_CLOSED);
        reportMapper.update(reportPo, Wrappers.<AbnormalReportPO>lambdaUpdate()
                .eq(AbnormalReportPO::getId, alert.getReportId())
                .ne(AbnormalReportPO::getStatus, AbnormalReport.STATUS_CLOSED));
    }
}
