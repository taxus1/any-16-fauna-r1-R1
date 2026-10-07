package com.somepro.application.shared.guard;

import com.somepro.common.exception.BizException;
import com.somepro.domain.site.model.MonitorSite;
import com.somepro.domain.site.repository.MonitorSiteRepository;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * 监测点准入校验（应用层共享）：所有「往点上办业务」的入口都来这取点、这判断，
 * 全仓就这一份「点得存在」的取数口径。
 *
 * 派巡护任务额外要求点在册（{@link #requireActive}）；观测录入只要求点还在册库里
 * （撤掉的点不挂观测），走 {@link #requireExisting}。判断在领域对象上
 * （{@link MonitorSite#active()}），这里只管取数与按入口组织报错文案。
 */
@Component
public class SiteGuard {

    private final MonitorSiteRepository siteRepository;

    public SiteGuard(MonitorSiteRepository siteRepository) {
        this.siteRepository = siteRepository;
    }

    /**
     * 取点：查不到（已撤掉的 @TableLogic 也查不到）按入口文案拒绝。
     *
     * @param nullMessage     没传点 id 时的报错文案
     * @param missingMessage  点查不到时的报错文案
     */
    public Mono<MonitorSite> requireExisting(Long siteId, String nullMessage, String missingMessage) {
        if (siteId == null) {
            return Mono.error(new BizException(nullMessage));
        }
        return siteRepository.findById(siteId)
                .switchIfEmpty(Mono.error(new BizException(missingMessage)));
    }

    /** 取点并要求在册（ACTIVE）：停测/撤掉的点不能再往那儿派巡护任务。 */
    public Mono<MonitorSite> requireActive(Long siteId, String nullMessage, String missingMessage,
                                           String inactiveMessage) {
        return requireExisting(siteId, nullMessage, missingMessage)
                .flatMap(site -> site.active()
                        ? Mono.just(site)
                        : Mono.<MonitorSite>error(new BizException(inactiveMessage)));
    }
}
