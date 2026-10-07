package com.somepro.application.report;

import com.somepro.common.exception.BizException;
import com.somepro.domain.obs.model.WildlifeObs;
import com.somepro.domain.report.model.AbnormalReport;
import com.somepro.domain.report.repository.AbnormalReportRepository;
import com.somepro.domain.report.service.ReportAdmissionGuard;
import com.somepro.domain.shared.model.PageResult;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;

/**
 * 异常个体上报应用层：编排上报用例（登记、处置推进、查看、作废、条件分页）。
 *
 * 「能不能收」（观测在册且非正常、挂的巡护任务未取消）的取数与判断统一收在
 * {@link ReportAdmissionGuard}，本类只做用例编排。
 * 「同一观测只挂一条未作废上报」由仓储层在登记事务内兜底：两人前后脚一起递只落一条，
 * 原来那条作废了再重报才放行。
 *
 * 类别与观测健康状态的对口、严重程度的推算（死亡/疑似疫病一律高；受伤看观测上的
 * 保护级别快照，国家一级/二级算高、其余算中）是领域规则，由领域对象把守。
 */
@Service
public class AbnormalReportAppService {

    private final AbnormalReportRepository reportRepository;
    private final ReportAdmissionGuard guard;

    public AbnormalReportAppService(AbnormalReportRepository reportRepository,
                                    ReportAdmissionGuard guard) {
        this.reportRepository = reportRepository;
        this.guard = guard;
    }

    /**
     * 登记一条异常上报：编号 AR-YYYY-NNNN 由仓储层生成；严重程度由系统按来头算；
     * 上报时刻不传取登记当下；立起来落在已上报。
     */
    public Mono<AbnormalReport> report(Long obsId, String category, LocalDateTime reportedAt) {
        return guard.requireReportableObs(obsId)
                .flatMap(obs -> guard.requireTaskNotCancelled(obs.getTaskId())
                        .then(Mono.defer(() -> {
                            AbnormalReport report = AbnormalReport.create(obs.getId(), obs.getSiteId(),
                                    category, obs.getHealthStatus(), obs.getProtectionLevel(), reportedAt);
                            return reportRepository.create(report);
                        })));
    }

    /**
     * 处置推进：已上报→处置中→已救护/已采样→已结案，只能顺着走，不能跳级不能回退，
     * 已结案的再推挡回（领域对象拦）；并发推同一单由条件更新兜底，只有一下翻得动。
     * 推进时处置时刻由审计列 update_time 记下。
     */
    public Mono<AbnormalReport> advance(Long id, String targetStatus) {
        return reportRepository.findById(id)
                .switchIfEmpty(Mono.error(new BizException("异常上报不存在")))
                .flatMap(report -> {
                    String fromStatus = report.getStatus();
                    report.advance(targetStatus);
                    return reportRepository.advance(report, fromStatus)
                            .flatMap(flipped -> flipped
                                    // 重查一遍：处置时刻由审计列在落库时刷新，内存里的还是推进前的
                                    ? reportRepository.findById(report.getId())
                                    : Mono.error(new BizException("上报状态已变化，请刷新后重试")));
                });
    }

    /** 查看单条在册上报（已作废的翻不到，账仍留在库里）。 */
    public Mono<AbnormalReport> detail(Long id) {
        return reportRepository.findById(id)
                .switchIfEmpty(Mono.error(new BizException("异常上报不存在")));
    }

    /** 作废：逻辑删除（del_flag=1），名单里不再翻到，账留在库里；作废后该观测可重报。 */
    public Mono<Void> voidReport(Long id) {
        return reportRepository.findById(id)
                .switchIfEmpty(Mono.error(new BizException("异常上报不存在")))
                .flatMap(report -> reportRepository.voidReport(report.getId()));
    }

    /** 条件分页：点位/类别/严重程度/状态随意拼，全空翻整份在册上报，每行带上报编号。 */
    public Mono<PageResult<AbnormalReport>> pageReports(int pageNum, int pageSize,
                                                        Long siteId, String category,
                                                        String severity, String status) {
        return reportRepository.page(pageNum, pageSize, siteId,
                normalize(category), normalize(severity), normalize(status));
    }

    private static String normalize(String value) {
        return (value == null || value.isBlank()) ? null : value.trim();
    }
}
