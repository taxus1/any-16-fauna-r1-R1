package com.somepro.application.shared.guard;

import com.somepro.common.exception.BizException;
import com.somepro.domain.obs.model.WildlifeObs;
import com.somepro.domain.obs.repository.WildlifeObsRepository;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * 观测准入校验（应用层共享）：异常上报要先把来源观测捞出来验明正身 —— 取数与判断
 * 全仓就这一份。判断在领域对象上（{@link WildlifeObs#abnormal()}）。
 */
@Component
public class ObservationGuard {

    private final WildlifeObsRepository obsRepository;

    public ObservationGuard(WildlifeObsRepository obsRepository) {
        this.obsRepository = obsRepository;
    }

    /**
     * 上报前置：观测得在册（已作废的 @TableLogic 查不到），且健康状态非正常。
     */
    public Mono<WildlifeObs> requireReportable(Long obsId) {
        if (obsId == null) {
            return Mono.error(new BizException("来源观测不能为空"));
        }
        return obsRepository.findById(obsId)
                .switchIfEmpty(Mono.error(new BizException("观测记录不存在或已作废，不能上报")))
                .flatMap(obs -> obs.abnormal()
                        ? Mono.just(obs)
                        : Mono.<WildlifeObs>error(new BizException("健康状态正常的观测不能上报异常")));
    }
}
