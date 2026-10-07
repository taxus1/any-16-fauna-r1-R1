package com.somepro.infrastructure.persistence.sample;

import cn.hutool.core.util.IdUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.somepro.common.exception.BizException;
import com.somepro.domain.alert.model.EpiAlert;
import com.somepro.domain.sample.model.SampleTest;
import com.somepro.infrastructure.persistence.alert.EpiAlertMapper;
import com.somepro.infrastructure.persistence.alert.converter.EpiAlertPoConverter;
import com.somepro.infrastructure.persistence.alert.po.EpiAlertPO;
import com.somepro.infrastructure.persistence.obs.WildlifeObsMapper;
import com.somepro.infrastructure.persistence.obs.po.WildlifeObsPO;
import com.somepro.infrastructure.persistence.report.AbnormalReportMapper;
import com.somepro.infrastructure.persistence.report.po.AbnormalReportPO;
import com.somepro.infrastructure.persistence.support.BizNoGenerator;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * 阳性样本立疫病预警（基础设施层共享组件）：样本检测结果回填事务里的「预警那一头」。
 *
 * 之前这段取数与判断塞在 {@code SampleTestRepositoryImpl#recordResult} 的大事务方法里，
 * 跟样本翻结果、上报推已采样三件事缠在一起；拆到这以后，「阳性怎么立预警」的取数
 * （取上报、取来源观测、数有没有已立过的预警）全仓只在这一处。
 *
 * 必须在调用方的事务里执行：同一份阳性样本只落一条 —— 样本行已被调用方的
 * result=PENDING 条件更新锁住到事务提交，前后脚递两回在样本侧就只放行一下；
 * 这里再数一道兜底，已有就不再落。编号 AL-YYYY-NNNN 取号走锁定读
 * （{@link EpiAlertMapper#selectMaxNoForUpdate}），撞号重取，不甩底层错。
 * 预警级别照领域工厂 {@link EpiAlert#raise} 算（上报严重程度叠观测保护级别快照）。
 */
@Component
public class PositiveSampleAlertRaiser {

    /** 预警编号前缀：AL-（完整形如 AL-2026-） */
    private static final String ALERT_NO_PREFIX = "AL-";

    private final AbnormalReportMapper reportMapper;
    private final WildlifeObsMapper obsMapper;
    private final EpiAlertMapper alertMapper;

    public PositiveSampleAlertRaiser(AbnormalReportMapper reportMapper,
                                     WildlifeObsMapper obsMapper,
                                     EpiAlertMapper alertMapper) {
        this.reportMapper = reportMapper;
        this.obsMapper = obsMapper;
        this.alertMapper = alertMapper;
    }

    /**
     * 阳性样本立预警（在检测结果回填的同一个事务里被调用）。
     * 不是阳性不立；同一样本已立过则幂等返回，不重复落。
     */
    public void raiseForPositiveSample(SampleTest sample) {
        if (!sample.positive()) {
            return;
        }
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
        EpiAlert alert = EpiAlert.raise(sample.getReportId(), sample.getId(),
                reportPo.getSeverity(), obsPo.getProtectionLevel());
        insertWithGeneratedNo(alert);
    }

    /**
     * 取号走锁定读（当前读）：大事务（REPEATABLE READ）里的一致读快照是固定的，
     * 靠普通 SELECT MAX 取号重试会反复撞同一个旧号甚至搅出死锁；锁定读让并发立预警
     * 在号段锁上排队，各取新号，不甩底层冲突。
     */
    private void insertWithGeneratedNo(EpiAlert alert) {
        String prefix = ALERT_NO_PREFIX + LocalDate.now().getYear() + "-";
        BizNoGenerator.insertWithRetry(
                () -> {
                    String maxNo = alertMapper.selectMaxNoForUpdate(prefix, prefix.length() + 1);
                    return maxNo == null ? null : Long.valueOf(maxNo.substring(prefix.length()));
                },
                prefix,
                no -> {
                    alert.setAlertNo(no);
                    EpiAlertPO po = EpiAlertPoConverter.toPo(alert);
                    po.setId(IdUtil.getSnowflakeNextId());
                    alertMapper.insert(po);
                    return EpiAlertPoConverter.toDomain(po);
                });
    }
}
