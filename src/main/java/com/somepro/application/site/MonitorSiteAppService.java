package com.somepro.application.site;

import com.somepro.common.exception.BizException;
import com.somepro.domain.shared.model.PageResult;
import com.somepro.domain.site.model.MonitorSite;
import com.somepro.domain.site.repository.MonitorSiteRepository;
import com.somepro.domain.station.model.MonitorStation;
import com.somepro.domain.task.service.PatrolDispatchGuard;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * 监测点应用层：编排监测点用例（登记、改资料、查看、停测/恢复、撤点、名册分页）。
 *
 * 挂载规则：点位只能挂到「实实在在存在、且未停用未关闭」的监测站上 —— 与派任务时
 * 「站得在运行」是同一条判断、同一次取数，统一走 {@link PatrolDispatchGuard}
 * （报错文案按挂载场景不同由守卫内部分别给出）；登记与改挂载都调它。
 */
@Service
public class MonitorSiteAppService {

    private final MonitorSiteRepository siteRepository;
    private final PatrolDispatchGuard dispatchGuard;

    public MonitorSiteAppService(MonitorSiteRepository siteRepository,
                                 PatrolDispatchGuard dispatchGuard) {
        this.siteRepository = siteRepository;
        this.dispatchGuard = dispatchGuard;
    }

    /** 登记监测点：默认在册；siteNo 留空时由仓储层生成。 */
    public Mono<MonitorSite> createSite(String siteNo, Long stationId, String siteType,
                                        String habitat, String location) {
        return requireAttachableStation(stationId)
                .flatMap(station -> {
                    MonitorSite site = MonitorSite.create(stationId, siteType, habitat, location);
                    site.setSiteNo(normalizeNo(siteNo));
                    return siteRepository.create(site);
                });
    }

    /**
     * 改资料：类型/生境/位置传啥改啥；stationId 与当前不同视为改挂，
     * 改挂的目标站同样要过「存在且未停用未关闭」校验。
     */
    public Mono<MonitorSite> updateSite(Long id, Long stationId, String siteType,
                                        String habitat, String location) {
        return siteRepository.findById(id)
                .switchIfEmpty(Mono.error(new BizException("监测点不存在")))
                .flatMap(site -> {
                    Mono<MonitorSite> validated = (stationId != null && !stationId.equals(site.getStationId()))
                            ? requireAttachableStation(stationId).map(station -> {
                                site.attachTo(stationId);
                                return site;
                            })
                            : Mono.just(site);
                    return validated.flatMap(s -> {
                        s.updateProfile(siteType, habitat, location);
                        return siteRepository.update(s);
                    });
                });
    }

    public Mono<MonitorSite> detail(Long id) {
        return siteRepository.findById(id)
                .switchIfEmpty(Mono.error(new BizException("监测点不存在")));
    }

    public Mono<MonitorSite> deactivate(Long id) {
        return changeStatus(id, true);
    }

    public Mono<MonitorSite> activate(Long id) {
        return changeStatus(id, false);
    }

    /** 撤点：逻辑删除，账留在表里。 */
    public Mono<Void> remove(Long id) {
        return siteRepository.findById(id)
                .switchIfEmpty(Mono.error(new BizException("监测点不存在")))
                .flatMap(site -> siteRepository.softDelete(id));
    }

    public Mono<PageResult<MonitorSite>> pageSites(int pageNum, int pageSize,
                                                   Long stationId, String siteType,
                                                   String habitat, String status) {
        return siteRepository.page(pageNum, pageSize, stationId, siteType, habitat, status);
    }

    private Mono<MonitorSite> changeStatus(Long id, boolean deactivate) {
        return siteRepository.findById(id)
                .switchIfEmpty(Mono.error(new BizException("监测点不存在")))
                .flatMap(site -> {
                    if (deactivate) {
                        site.deactivate();
                    } else {
                        site.activate();
                    }
                    return siteRepository.update(site);
                });
    }

    /**
     * 挂载校验：站必须存在，且在运行（停用/关闭的站不能再挂点位）。
     * 与派任务共用同一次取数与同一条「站在运行」判断（{@link PatrolDispatchGuard}），
     * 只是状态不符时的报错文案按挂载场景说。
     */
    private Mono<MonitorStation> requireAttachableStation(Long stationId) {
        return dispatchGuard.requireOperationalStation(stationId, "不能挂载监测点");
    }

    private static String normalizeNo(String siteNo) {
        return (siteNo == null || siteNo.isBlank()) ? null : siteNo.trim();
    }
}
