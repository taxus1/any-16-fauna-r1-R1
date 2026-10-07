package com.somepro.infrastructure.persistence.sample;

import cn.hutool.core.util.IdUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.somepro.common.exception.BizException;
import com.somepro.domain.report.model.AbnormalReport;
import com.somepro.domain.sample.model.SampleTest;
import com.somepro.domain.sample.repository.SampleTestRepository;
import com.somepro.domain.shared.model.PageResult;
import com.somepro.infrastructure.persistence.report.AbnormalReportMapper;
import com.somepro.infrastructure.persistence.report.po.AbnormalReportPO;
import com.somepro.infrastructure.persistence.sample.converter.SampleTestPoConverter;
import com.somepro.infrastructure.persistence.sample.po.SampleTestPO;
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
 * 采样送检与检测仓储适配器（基础设施层）。
 *
 * 编号分配：sampleNo 按 SM-YYYY-NNNN 生成（年份按登记当下，序号 4 位零填充），
 * 并发撞号由 {@link BizNoGenerator} 重试，唯一索引兜底，一个号只落一条；
 * 删除记录占用的编号不复用（selectMaxSeq 的自定义 @Select 不拼 del_flag）。
 *
 * 检测结果回填是「三头一起动」，一个事务里按职责分三步（见 {@link #recordResult}）：
 * 1. 样本按 result=PENDING 条件更新翻结果（同一条样本的结果只翻得动一次）；
 * 2. 上报从在办（已上报/处置中）条件更新推到已采样，已不在在办的按现状放行或回滚；
 * 3. 阳性立预警委托 {@link PositiveSampleAlertRaiser}（同事务，取数判断在它那一处）。
 */
@Repository
public class SampleTestRepositoryImpl implements SampleTestRepository {

    /** 编号前缀：SM-（完整形如 SM-2026-） */
    private static final String NO_PREFIX = "SM-";

    private final SampleTestMapper sampleMapper;
    private final AbnormalReportMapper reportMapper;
    private final PositiveSampleAlertRaiser alertRaiser;
    private final TransactionTemplate transactionTemplate;

    public SampleTestRepositoryImpl(SampleTestMapper sampleMapper,
                                    AbnormalReportMapper reportMapper,
                                    PositiveSampleAlertRaiser alertRaiser,
                                    PlatformTransactionManager transactionManager) {
        this.sampleMapper = sampleMapper;
        this.reportMapper = reportMapper;
        this.alertRaiser = alertRaiser;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Override
    public Mono<SampleTest> create(SampleTest sample) {
        return BlockingJdbc.blocking(() -> {
            String prefix = NO_PREFIX + LocalDate.now().getYear() + "-";
            return BizNoGenerator.insertWithRetry(
                    () -> sampleMapper.selectMaxSeq(prefix, prefix.length() + 1),
                    prefix,
                    no -> doInsert(sample, no));
        });
    }

    @Override
    public Mono<SampleTest> findById(Long id) {
        return BlockingJdbc.blocking(() -> {
            SampleTestPO po = sampleMapper.selectById(id);
            return po == null ? null : SampleTestPoConverter.toDomain(po);
        });
    }

    @Override
    public Mono<SampleTest> recordResult(SampleTest sample) {
        return BlockingJdbc.blocking(() -> transactionTemplate.execute(txStatus -> {
            flipSampleResult(sample);
            advanceReportToSampled(sample);
            // 阳性这一头的取数+判断全在 PositiveSampleAlertRaiser 一处；不是阳性它直接不动
            alertRaiser.raiseForPositiveSample(sample);
            return SampleTestPoConverter.toDomain(sampleMapper.selectById(sample.getId()));
        }));
    }

    @Override
    public Mono<PageResult<SampleTest>> page(int pageNum, int pageSize,
                                             Long reportId, String sampleType, String result) {
        return PageQueries.page(pageNum, pageSize,
                () -> sampleMapper.selectList(Wrappers.<SampleTestPO>lambdaQuery()
                        .eq(reportId != null, SampleTestPO::getReportId, reportId)
                        .eq(hasText(sampleType), SampleTestPO::getSampleType, sampleType)
                        .eq(hasText(result), SampleTestPO::getResult, result)
                        .orderByAsc(SampleTestPO::getId)),
                SampleTestPoConverter::toDomain);
    }

    /**
     * 样本侧：按 result=PENDING 条件更新，同一条样本的结果只翻得动一次；
     * 已出结果的再来录 0 行拦下（领域层已拦一道，这里兜并发）。
     */
    private void flipSampleResult(SampleTest sample) {
        SampleTestPO samplePo = new SampleTestPO();
        samplePo.setResult(sample.getResult());
        samplePo.setTestedAt(sample.getTestedAt());
        int rows = sampleMapper.update(samplePo, Wrappers.<SampleTestPO>lambdaUpdate()
                .eq(SampleTestPO::getId, sample.getId())
                .eq(SampleTestPO::getResult, SampleTest.RESULT_PENDING));
        if (rows != 1) {
            throw new BizException("该样本检测结果已录入，同一条样本不重复录入");
        }
    }

    /**
     * 上报侧：从在办（已上报/处置中）推到已采样，与样本结果同一个事务落。
     * 0 行 = 上报已不在在办：已采样是幂等放行（同一份上报前一条样本录结果时推过）；
     * 已救护/已结案则这单已不走采样线，已作废的查不到，一并回滚报错，样本那行也不落。
     */
    private void advanceReportToSampled(SampleTest sample) {
        AbnormalReportPO reportPo = new AbnormalReportPO();
        reportPo.setStatus(AbnormalReport.STATUS_SAMPLED);
        int rows = reportMapper.update(reportPo, Wrappers.<AbnormalReportPO>lambdaUpdate()
                .eq(AbnormalReportPO::getId, sample.getReportId())
                .in(AbnormalReportPO::getStatus,
                        AbnormalReport.STATUS_REPORTED, AbnormalReport.STATUS_HANDLING));
        if (rows == 1) {
            return;
        }
        AbnormalReportPO current = reportMapper.selectById(sample.getReportId());
        if (current == null) {
            throw new BizException("异常上报不存在或已作废，检测结果回填失败");
        }
        if (!AbnormalReport.STATUS_SAMPLED.equals(current.getStatus())) {
            throw new BizException("上报已结案或已不走采样线，检测结果回填失败");
        }
    }

    private SampleTest doInsert(SampleTest sample, String sampleNo) {
        sample.setSampleNo(sampleNo);
        SampleTestPO po = SampleTestPoConverter.toPo(sample);
        po.setId(IdUtil.getSnowflakeNextId());
        sampleMapper.insert(po);
        return SampleTestPoConverter.toDomain(po);
    }
}
