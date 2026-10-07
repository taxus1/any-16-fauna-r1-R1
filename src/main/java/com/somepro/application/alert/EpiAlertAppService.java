package com.somepro.application.alert;

import com.somepro.application.shared.AdvanceTemplate;
import com.somepro.common.exception.BizException;
import com.somepro.domain.alert.model.EpiAlert;
import com.somepro.domain.alert.repository.EpiAlertRepository;
import com.somepro.domain.shared.model.PageResult;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;

/**
 * 疫病预警与处置应用层：编排预警用例（处置推进、查看、条件分页）。
 *
 * 预警的「立」不在这里编排：样本检测那条链上结果一录成阳性，预警就跟着冒出来
 * （样本仓储的同一个事务里落），不等人另外点一下；结果不是阳性的立不出来。
 *
 * 状态机只顺不逆（已发布→处置中→已解除→已归档，不跳级、不回退，归档为终态）由领域对象把守；
 * 并发推同一条由仓储层条件更新兜底，只有一下翻得动。解除这一步挂的上报跟着收尾到已结案，
 * 由仓储层在推进事务里联动。推进编排放进 {@link AdvanceTemplate}，与异常上报共用同一套形状。
 */
@Service
public class EpiAlertAppService {

    private final EpiAlertRepository alertRepository;

    public EpiAlertAppService(EpiAlertRepository alertRepository) {
        this.alertRepository = alertRepository;
    }

    /**
     * 处置推进：已发布→处置中→已解除→已归档，只能顺着走，不能跳级不能回退，
     * 已归档的再推挡回（领域对象拦）；并发推同一条由条件更新兜底，只有一下翻得动。
     * 每推一步的时刻由审计列 update_time 记下；推进到已解除时记解除时刻（不能早于发布时刻），
     * 挂的那条上报还没结案的跟着推到已结案，已经结案的照旧。
     */
    public Mono<EpiAlert> advance(Long id, String targetStatus, String disposalMethod,
                                  LocalDateTime resolvedAt) {
        return AdvanceTemplate.advance(id,
                alertRepository::findById,
                "预警不存在",
                EpiAlert::getStatus,
                alert -> alert.advance(targetStatus, disposalMethod, resolvedAt),
                alertRepository::advance,
                alert -> alertRepository.findById(alert.getId()),
                "预警状态已变化，请刷新后重试");
    }

    /** 查看单条在册预警。 */
    public Mono<EpiAlert> detail(Long id) {
        return alertRepository.findById(id)
                .switchIfEmpty(Mono.error(new BizException("预警不存在")));
    }

    /** 条件分页：上报/样本/级别/状态随意拼，全空翻整份在册预警，每行带预警编号。 */
    public Mono<PageResult<EpiAlert>> pageAlerts(int pageNum, int pageSize,
                                                 Long reportId, Long sampleId,
                                                 String alertLevel, String status) {
        return alertRepository.page(pageNum, pageSize, reportId, sampleId,
                normalize(alertLevel), normalize(status));
    }

    private static String normalize(String value) {
        return (value == null || value.isBlank()) ? null : value.trim();
    }
}
