package com.somepro.domain.task.service;

import com.somepro.common.exception.BizException;
import com.somepro.domain.site.model.MonitorSite;
import com.somepro.domain.site.repository.MonitorSiteRepository;
import com.somepro.domain.station.model.MonitorStation;
import com.somepro.domain.station.repository.MonitorStationRepository;
import com.somepro.domain.task.model.PatrolTask;
import com.somepro.domain.task.repository.PatrolTaskRepository;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.LocalDate;

/**
 * 巡护任务派发守卫（领域服务）：把「一条任务能不能派」的取数与判断收在一处。
 *
 * 派发门槛（看两头 + 占位）：
 * - 站得在运行（ACTIVE）：停用/关闭的站不再往那儿派；
 * - 点得在册（ACTIVE）：停测/撤掉的点不再往那儿派；
 * - 同一个点同一天只挂一条还没走完（待执行/执行中）的任务，已取消的不占位。
 *
 * 新派与改任务（换站/换点/改计划日期）都走这里，判断不再各写一遍；
 * 状态本身的字段级不变量仍由 {@link PatrolTask} 自己守。
 */
@Service
public class PatrolDispatchGuard {

    private final MonitorStationRepository stationRepository;
    private final MonitorSiteRepository siteRepository;
    private final PatrolTaskRepository taskRepository;

    public PatrolDispatchGuard(MonitorStationRepository stationRepository,
                               MonitorSiteRepository siteRepository,
                               PatrolTaskRepository taskRepository) {
        this.stationRepository = stationRepository;
        this.siteRepository = siteRepository;
        this.taskRepository = taskRepository;
    }

    /** 站必须存在且在运行：停用/关闭的站不能再往那儿派任务。 */
    public Mono<MonitorStation> requireDispatchableStation(Long stationId) {
        return requireOperationalStation(stationId, "不能派发巡护任务");
    }

    /**
     * 站必须存在且在运行（通用把关，挂点/派任务共用这一次取数与判断）。
     *
     * @param actionForStatusError 状态不符时拼进报错的动作说明（如「不能派发巡护任务」），
     *                             「不存在 / 为空」的报错不受场景影响，固定不变
     */
    public Mono<MonitorStation> requireOperationalStation(Long stationId, String actionForStatusError) {
        if (stationId == null) {
            return Mono.error(new BizException("所属监测站不能为空"));
        }
        return stationRepository.findById(stationId)
                .switchIfEmpty(Mono.error(new BizException("所属监测站不存在")))
                .flatMap(station -> {
                    // 「在运行」口径只认模型上的 isOperational()，停用/关闭各自给出对应文案
                    if (MonitorStation.STATUS_CLOSED.equals(station.getStatus())) {
                        return Mono.error(new BizException("监测站已关闭，" + actionForStatusError));
                    }
                    if (!station.isOperational()) {
                        return Mono.error(new BizException("监测站已停用，" + actionForStatusError));
                    }
                    return Mono.just(station);
                });
    }    /** 点必须存在且在册：停测的点不能再往那儿派任务。 */
    public Mono<MonitorSite> requireDispatchableSite(Long siteId) {
        if (siteId == null) {
            return Mono.error(new BizException("监测点不能为空"));
        }
        return siteRepository.findById(siteId)
                .switchIfEmpty(Mono.error(new BizException("监测点不存在")))
                .flatMap(site -> {
                    if (!site.isActive()) {
                        return Mono.error(new BizException("监测点已停测，不能派发巡护任务"));
                    }
                    return Mono.just(site);
                });
    }

    /** 同点同日占位校验：已有没走完的任务（待执行/执行中）就先别派；excludeId 为改任务时的自身 id。 */
    public Mono<Void> requireSiteDateFree(Long siteId, LocalDate plannedDate, Long excludeId) {
        return taskRepository.countUnfinishedBySiteAndDate(siteId, plannedDate, excludeId)
                .flatMap(count -> count > 0
                        ? Mono.<Void>error(new BizException("该监测点当天已有未完成的巡护任务，待其完成或取消后再派"))
                        : Mono.<Void>empty());
    }
}
