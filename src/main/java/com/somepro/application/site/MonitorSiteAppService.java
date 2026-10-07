package com.somepro.application.site;

import com.somepro.application.shared.guard.StationGuard;
import com.somepro.common.exception.BizException;
import com.somepro.domain.shared.model.PageResult;
import com.somepro.domain.site.model.MonitorSite;
import com.somepro.domain.site.repository.MonitorSiteRepository;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * 监测点应用层：编排监测点用例（登记、改资料、查看、停测/恢复、撤点、名册分页）。
 *
 * 挂载规则：点位只能挂到「实实在在存在、且未停用未关闭」的监测站上 ——
 * 登记时校验，改挂载站时同样校验；目标站不存在、已停用、已关闭都直接业务异常。
 * 这份「站存在且在运行」的取数+判断与派巡护任务共用 {@link StationGuard}，全仓只一处。
 */
@Service
public class MonitorSiteAppService {

    private final MonitorSiteRepository siteRepository;
    private final StationGuard stationGuard;

    public MonitorSiteAppService(MonitorSiteRepository siteRepository,
                                 StationGuard stationGuard) {
        this.siteRepository = siteRepository;
        this.stationGuard = stationGuard;
    }

    /** 登记监测点：默认在册；siteNo 留空时由仓储层生成。 */
    public Mono<MonitorSite> createSite(String siteNo, Long stationId, String siteType,
                                        String habitat, String location) {
        return stationGuard.requireOperating(stationId, "所属监测站不能为空", "所属监测站不存在", "挂载监测点")
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
                            ? stationGuard.requireOperating(stationId, "所属监测站不能为空",
                                    "所属监测站不存在", "挂载监测点")
                                    .map(station -> {
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

    private static String normalizeNo(String siteNo) {
        return (siteNo == null || siteNo.isBlank()) ? null : siteNo.trim();
    }
}
