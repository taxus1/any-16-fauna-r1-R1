package com.somepro.application.sample;

import com.somepro.application.shared.guard.ReportGuard;
import com.somepro.common.exception.BizException;
import com.somepro.domain.sample.model.SampleTest;
import com.somepro.domain.sample.repository.SampleTestRepository;
import com.somepro.domain.shared.model.PageResult;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;

/**
 * 采样送检与检测应用层：编排采样用例（登记、检测结果回填、查看、条件分页）。
 *
 * 登记前置「挂的那条上报得还在办（已上报/处置中）」的取数+判断走共享
 * {@link ReportGuard#requireSamplable}，全仓只此一份 —— 已结案 CLOSED 的别再采样，
 * 已救护/已采样的也不在在办状态，同样采不了。
 *
 * 结果回填的联动（样本翻结果 + 上报推已采样 + 阳性立预警，一个事务三头一起动）由仓储层落；
 * 「结果只录一次、同一条样本别来回翻」由领域对象拦一道、仓储条件更新兜底。
 * 结果一录成阳性，这条预警就跟着冒出来，不等人另外点一下；结果不是阳性的立不出来。
 */
@Service
public class SampleTestAppService {

    private final SampleTestRepository sampleRepository;
    private final ReportGuard reportGuard;

    public SampleTestAppService(SampleTestRepository sampleRepository,
                                ReportGuard reportGuard) {
        this.sampleRepository = sampleRepository;
        this.reportGuard = reportGuard;
    }

    /**
     * 登记一份样本：编号 SM-YYYY-NNNN 由仓储层生成；送检时刻不传取登记当下；
     * 立起来落在待检。
     */
    public Mono<SampleTest> register(Long reportId, String sampleType, LocalDateTime sentAt,
                                     String labName, String testItem) {
        return reportGuard.requireSamplable(reportId)
                .flatMap(report -> {
                    SampleTest sample = SampleTest.create(report.getId(), sampleType, sentAt,
                            labName, testItem);
                    return sampleRepository.create(sample);
                });
    }

    /**
     * 检测结果回填：结果只录一次，同一条样本别来回翻；录进去的同时上报从在办推到
     * 已采样，阳性样本再跟着立一条预警，三头一起动；结果还悬着没出的，上报那头先别动。
     */
    public Mono<SampleTest> recordResult(Long id, String result, LocalDateTime testedAt) {
        return sampleRepository.findById(id)
                .switchIfEmpty(Mono.error(new BizException("样本不存在")))
                .flatMap(sample -> {
                    sample.recordResult(result, testedAt);
                    return sampleRepository.recordResult(sample);
                });
    }

    /** 查看单条在册样本。 */
    public Mono<SampleTest> detail(Long id) {
        return sampleRepository.findById(id)
                .switchIfEmpty(Mono.error(new BizException("样本不存在")));
    }

    /** 条件分页：上报/样本类型/结果随意拼，全空翻整份在册样本，每行带样本编号。 */
    public Mono<PageResult<SampleTest>> pageSamples(int pageNum, int pageSize,
                                                    Long reportId, String sampleType, String result) {
        return sampleRepository.page(pageNum, pageSize, reportId,
                normalize(sampleType), normalize(result));
    }

    private static String normalize(String value) {
        return (value == null || value.isBlank()) ? null : value.trim();
    }
}
