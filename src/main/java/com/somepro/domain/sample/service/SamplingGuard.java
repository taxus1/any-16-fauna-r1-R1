package com.somepro.domain.sample.service;

import com.somepro.common.exception.BizException;
import com.somepro.domain.report.model.AbnormalReport;
import com.somepro.domain.report.repository.AbnormalReportRepository;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * 样本登记守卫（领域服务）：把「一份样本能不能登」的取数与判断收在一处。
 *
 * 登记前置：挂的那条上报得在册、且还在办（已上报/处置中，口径走
 * {@link AbnormalReport#isOngoing()}）—— 已结案 CLOSED 的别再采样，
 * 已救护/已采样的也不在在办状态，同样采不了。
 *
 * 「检测结果只录一次」由 {@link com.somepro.domain.sample.model.SampleTest} 拦一道、
 * 仓储条件更新兜底，不在这里。
 */
@Service
public class SamplingGuard {

    private final AbnormalReportRepository reportRepository;

    public SamplingGuard(AbnormalReportRepository reportRepository) {
        this.reportRepository = reportRepository;
    }

    /** 上报得在册且还在办：已结案的别再采样，已救护/已采样的也不在在办状态。 */
    public Mono<AbnormalReport> requireSamplableReport(Long reportId) {
        if (reportId == null) {
            return Mono.error(new BizException("所属上报不能为空"));
        }
        return reportRepository.findById(reportId)
                .switchIfEmpty(Mono.error(new BizException("异常上报不存在或已作废，不能采样")))
                .flatMap(report -> {
                    if (report.isClosed()) {
                        return Mono.error(new BizException("上报已结案，不能再采样"));
                    }
                    if (!report.isOngoing()) {
                        return Mono.error(new BizException("上报已不在在办状态（已救护/已采样），不能再采样"));
                    }
                    return Mono.just(report);
                });
    }
}
