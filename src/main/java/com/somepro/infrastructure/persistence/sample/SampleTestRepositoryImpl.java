package com.somepro.infrastructure.persistence.sample;

import cn.hutool.core.util.IdUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.somepro.common.exception.BizException;
import com.somepro.domain.alert.model.EpiAlert;
import com.somepro.domain.alert.service.AlertRaisingPolicy;
import com.somepro.domain.report.model.AbnormalReport;
import com.somepro.domain.sample.model.SampleTest;
import com.somepro.domain.sample.repository.SampleTestRepository;
import com.somepro.domain.shared.model.PageResult;
import com.somepro.infrastructure.persistence.alert.EpiAlertMapper;
import com.somepro.infrastructure.persistence.alert.converter.EpiAlertPoConverter;
import com.somepro.infrastructure.persistence.alert.po.EpiAlertPO;
import com.somepro.infrastructure.persistence.obs.WildlifeObsMapper;
import com.somepro.infrastructure.persistence.obs.po.WildlifeObsPO;
import com.somepro.infrastructure.persistence.report.AbnormalReportMapper;
import com.somepro.infrastructure.persistence.report.po.AbnormalReportPO;
import com.somepro.infrastructure.persistence.sample.converter.SampleTestPoConverter;
import com.somepro.infrastructure.persistence.sample.po.SampleTestPO;
import com.somepro.infrastructure.persistence.support.BizNoGenerator;
import com.somepro.infrastructure.persistence.support.BlockingJdbc;
import com.somepro.infrastructure.persistence.support.Conditions;
import com.somepro.infrastructure.persistence.support.PagingQuery;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import reactor.core.publisher.Mono;

import java.time.LocalDate;

/**
 * 采样送检与检测仓储适配器（基础设施层）。
 *
 * 只负责样本本身的存取与检测结果回填的事务编排：编号分配、结果按 PENDING 条件更新、
 * 分页（阻塞 JDBC 走 {@link BlockingJdbc}、分页走 {@link PagingQuery}）。
 *
 * 编号分配：sampleNo 按 SM-YYYY-NNNN 生成（年份按登记当下，序号 4 位零填充），
 * 并发撞号由 {@link BizNoGenerator} 重试，唯一索引兜底，一个号只落一条；
 * 删除记录占用的编号不复用（selectMaxSeq 的自定义 @Select 不拼 del_flag）。
 *
 * 检测结果回填是「三头一起动」，但每头各是一个职责单一的小方法，事务编排只负责串起来：
 * - {@link #flipSampleResult}：按 result=PENDING 条件更新样本，同一条样本结果只翻得动一次；
 * - {@link #advanceReportToSampled}：把上报从在办（已上报/处置中）条件更新推到已采样，
 *   已采样幂等放行，已救护/已结案/已作废则整体回滚报错，样本那行也不落；
 * - {@link #raiseAlertForPositive}：结果阳性时在同一事务立预警（判定走
 *   {@link AlertRaisingPolicy}），不是阳性不立。
 */
@Repository
public class SampleTestRepositoryImpl implements SampleTestRepository {

    /** 编号前缀：SM-（完整形如 SM-2026-） */
    private static final String NO_PREFIX = "SM-";

    /** 预警编号前缀：AL-（完整形如 AL-2026-） */
    private static final String ALERT_NO_PREFIX = "AL-";

    private final SampleTestMapper sampleMapper;
    private final AbnormalReportMapper reportMapper;
    private final WildlifeObsMapper obsMapper;
    private final EpiAlertMapper alertMapper;
    private final AlertRaisingPolicy alertRaisingPolicy;
    private final TransactionTemplate transactionTemplate;

    public SampleTestRepositoryImpl(SampleTestMapper sampleMapper,
                                    AbnormalReportMapper reportMapper,
                                    WildlifeObsMapper obsMapper,
                                    EpiAlertMapper alertMapper,
                                    AlertRaisingPolicy alertRaisingPolicy,
                                    PlatformTransactionManager transactionManager) {
        this.sampleMapper = sampleMapper;
        this.reportMapper = reportMapper;
        this.obsMapper = obsMapper;
        this.alertMapper = alertMapper;
        this.alertRaisingPolicy = alertRaisingPolicy;
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
            // 一头：样本按 result=PENDING 条件翻结果（0 行拦下，兜并发重复录入）
            flipSampleResult(sample);
            // 二头：上报从在办条件更新推到已采样（已采样幂等放行，其余终态回滚报错）
            advanceReportToSampled(sample.getReportId());
            // 三头：阳性才在同一事务立预警；不是阳性的立不出来
            if (alertRaisingPolicy.shouldRaise(sample.getResult())) {
                raiseAlertForPositive(sample);
            }
            return SampleTestPoConverter.toDomain(sampleMapper.selectById(sample.getId()));
        }));
    }

    @Override
    public Mono<PageResult<SampleTest>> page(int pageNum, int pageSize,
                                             Long reportId, String sampleType, String result) {
        return BlockingJdbc.blocking(() -> PagingQuery.page(pageNum, pageSize,
                () -> sampleMapper.selectList(Wrappers.<SampleTestPO>lambdaQuery()
                        .eq(reportId != null, SampleTestPO::getReportId, reportId)
                        .eq(Conditions.hasText(sampleType), SampleTestPO::getSampleType, sampleType)
                        .eq(Conditions.hasText(result), SampleTestPO::getResult, result)
                        .orderByAsc(SampleTestPO::getId)),
                SampleTestPoConverter::toDomain));
    }

    /**
     * 样本侧：按 result=PENDING 条件更新，同一条样本的结果只翻得动一次；
     * 已出结果的再来录，0 行拦下（领域层已拦一道，这里兜并发）。
     */
    private void flipSampleResult(SampleTest sample) {
        SampleTestPO samplePo = new SampleTestPO();
        samplePo.setResult(sample.getResult());
        samplePo.setTestedAt(sample.getTestedAt());
        int sampleRows = sampleMapper.update(samplePo, Wrappers.<SampleTestPO>lambdaUpdate()
                .eq(SampleTestPO::getId, sample.getId())
                .eq(SampleTestPO::getResult, SampleTest.RESULT_PENDING));
        if (sampleRows != 1) {
            throw new BizException("该样本检测结果已录入，同一条样本不重复录入");
        }
    }

    /**
     * 上报侧：从在办（已上报/处置中）推到已采样，与样本结果同一个事务落。
     * 0 行 = 上报已不在在办：已采样是幂等（同一份上报前一条样本录结果时推过），放行；
     * 已救护/已结案则这单已不走采样线，已作废的查不到，一并回滚报错。
     */
    private void advanceReportToSampled(Long reportId) {
        AbnormalReportPO reportPo = new AbnormalReportPO();
        reportPo.setStatus(AbnormalReport.STATUS_SAMPLED);
        int reportRows = reportMapper.update(reportPo, Wrappers.<AbnormalReportPO>lambdaUpdate()
                .eq(AbnormalReportPO::getId, reportId)
                .in(AbnormalReportPO::getStatus,
                        AbnormalReport.STATUS_REPORTED, AbnormalReport.STATUS_HANDLING));
        if (reportRows == 1) {
            return;
        }
        AbnormalReportPO current = reportMapper.selectById(reportId);
        if (current == null) {
            throw new BizException("异常上报不存在或已作废，检测结果回填失败");
        }
        if (!AbnormalReport.STATUS_SAMPLED.equals(current.getStatus())) {
            throw new BizException("上报已结案或已不走采样线，检测结果回填失败");
        }
    }

    /**
     * 阳性样本立预警（在检测结果回填的同一个事务里）：级别在上报当初判定的严重程度之上，
     * 叠观测上抄的保护级别快照算（不跟名录后来的改动跑），立起来落在已发布。
     * 同一份阳性样本只落一条：样本行已被本事务的条件更新锁住到提交，前后脚递两回在样本侧
     * 就只放行一下；这里再数一道兜底，已有就不再落。编号 AL-YYYY-NNNN 撞号重取，不甩底层错。
     */
    private void raiseAlertForPositive(SampleTest sample) {
        AbnormalReportPO reportPo = reportMapper.selectById(sample.getReportId());
        if (reportPo == null) {
            throw new BizException("异常上报不存在或已作废，预警生成失败");
        }
        WildlifeObsPO obsPo = obsMapper.selectById(reportPo.getObsId());
        if (obsPo == null) {
            throw new BizException("来源观测不存在或已作废，预警生成失败");
        }
        Long existing = alertMapper.selectCount(Wrappers.<EpiAlertPO>lambdaQuery()
                .eq(EpiAlertPO::getSampleId, sample.getId()));
        if (existing != null && existing > 0) {
            return;
        }
        EpiAlert alert = alertRaisingPolicy.raise(sample.getReportId(), sample.getId(),
                reportPo.getSeverity(), obsPo.getProtectionLevel());
        String prefix = ALERT_NO_PREFIX + LocalDate.now().getYear() + "-";
        // 取号走锁定读（当前读）：本事务里的一致读快照是固定的，靠它取号重试会反复撞同一个
        // 旧号甚至搅出死锁；锁定读让并发立预警在号段锁上排队，各取新号，不甩底层错。
        BizNoGenerator.insertWithRetry(
                () -> {
                    String maxNo = alertMapper.selectMaxNoForUpdate(prefix, prefix.length() + 1);
                    return maxNo == null ? null : Long.valueOf(maxNo.substring(prefix.length()));
                },
                prefix,
                no -> insertAlert(alert, no));
    }

    private SampleTest doInsert(SampleTest sample, String sampleNo) {
        sample.setSampleNo(sampleNo);
        SampleTestPO po = SampleTestPoConverter.toPo(sample);
        po.setId(IdUtil.getSnowflakeNextId());
        sampleMapper.insert(po);
        return SampleTestPoConverter.toDomain(po);
    }

    private EpiAlert insertAlert(EpiAlert alert, String alertNo) {
        alert.setAlertNo(alertNo);
        EpiAlertPO po = EpiAlertPoConverter.toPo(alert);
        po.setId(IdUtil.getSnowflakeNextId());
        alertMapper.insert(po);
        return EpiAlertPoConverter.toDomain(po);
    }
}
