package com.somepro.domain.alert.service;

import com.somepro.domain.alert.model.EpiAlert;
import org.springframework.stereotype.Service;

/**
 * 疫病预警立案判定（领域服务，无状态）：把「一条预警能不能立、立成什么级别」的规则收在一处。
 *
 * 只有阳性结果才立预警；级别照上报口径往上接（{@link EpiAlert#raise} 里定死的算档）：
 * 上报当初判定的严重程度定基线，叠观测快照上的物种保护级别，合计落蓝/黄/橙/红。
 *
 * 样本检测结果回填在仓储事务里调它做判定，应用层与仓储都不再自己判阳性、自己算级别。
 */
@Service
public class AlertRaisingPolicy {

    /** 阳性结果才立预警，其余结果（阴性/不确定）立不出来。 */
    public boolean shouldRaise(String sampleResult) {
        return com.somepro.domain.sample.model.SampleTest.RESULT_POSITIVE.equals(sampleResult);
    }

    /** 按上报严重程度与观测保护级别快照立一条已发布预警（级别在领域工厂里算）。 */
    public EpiAlert raise(Long reportId, Long sampleId, String reportSeverity, String obsProtectionLevel) {
        return EpiAlert.raise(reportId, sampleId, reportSeverity, obsProtectionLevel);
    }
}
