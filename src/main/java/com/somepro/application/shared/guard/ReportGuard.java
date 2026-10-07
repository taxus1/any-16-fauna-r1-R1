package com.somepro.application.shared.guard;

import com.somepro.common.exception.BizException;
import com.somepro.domain.report.model.AbnormalReport;
import com.somepro.domain.report.repository.AbnormalReportRepository;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * 上报准入校验（应用层共享）：登记样本前要先把所属上报捞出来看还在不在办 ——
 * 取数与判断全仓就这一份。判断在领域对象上（{@link AbnormalReport#inHandling()}）。
 */
@Component
public class ReportGuard {

    private final AbnormalReportRepository reportRepository;

    public ReportGuard(AbnormalReportRepository reportRepository) {
        this.reportRepository = reportRepository;
    }

    /** 取上报：查不到（已作废的 @TableLogic 也查不到）按入口文案拒绝。 */
    public Mono<AbnormalReport> requireExisting(Long reportId, String missingMessage) {
        if (reportId == null) {
            return Mono.error(new BizException(missingMessage));
        }
        return reportRepository.findById(reportId)
                .switchIfEmpty(Mono.error(new BizException(missingMessage)));
    }

    /**
     * 采样登记前置：上报得在册且还在办（已上报/处置中）。
     * 已结案的别再采样；已救护/已采样的也不在在办状态，同样采不了。
     */
    public Mono<AbnormalReport> requireSamplable(Long reportId) {
        return requireExisting(reportId, "所属上报不能为空")
                .flatMap(report -> {
                    if (report.closed()) {
                        return Mono.error(new BizException("上报已结案，不能再采样"));
                    }
                    if (!report.inHandling()) {
                        return Mono.error(new BizException("上报已不在在办状态（已救护/已采样），不能再采样"));
                    }
                    return Mono.just(report);
                });
    }
}
