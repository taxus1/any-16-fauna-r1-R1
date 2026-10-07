package com.somepro.application.shared.guard;

import com.somepro.common.exception.BizException;
import com.somepro.domain.species.model.Species;
import com.somepro.domain.species.repository.SpeciesRepository;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * 物种准入校验（应用层共享）：录观测、改观测换物种都要验「编码在名录里且启用」——
 * 取数与判断全仓就这一份。判断在领域对象上（{@link Species#getStatus()}）。
 */
@Component
public class SpeciesGuard {

    private final SpeciesRepository speciesRepository;

    public SpeciesGuard(SpeciesRepository speciesRepository) {
        this.speciesRepository = speciesRepository;
    }

    /** 物种必须在名录里且启用：编码查不到/停用的都不收。入参已 trim 或为 null。 */
    public Mono<Species> requireEnabled(String speciesCode) {
        if (speciesCode == null || speciesCode.isBlank()) {
            return Mono.error(new BizException("物种编码不能为空"));
        }
        String code = speciesCode.trim();
        return speciesRepository.findByCode(code)
                .switchIfEmpty(Mono.error(new BizException("物种不在名录里，不能录入观测")))
                .flatMap(species -> Species.STATUS_ENABLED.equals(species.getStatus())
                        ? Mono.just(species)
                        : Mono.<Species>error(new BizException("物种已在名录中停用，不能再录入观测")));
    }
}
