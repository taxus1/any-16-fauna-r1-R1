package com.somepro.domain.obs.service;

import com.somepro.common.exception.BizException;
import com.somepro.domain.obs.model.WildlifeObs;
import com.somepro.domain.site.model.MonitorSite;
import com.somepro.domain.site.repository.MonitorSiteRepository;
import com.somepro.domain.species.model.Species;
import com.somepro.domain.species.repository.SpeciesRepository;
import com.somepro.domain.task.model.PatrolTask;
import com.somepro.domain.task.repository.PatrolTaskRepository;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * 观测录入守卫（领域服务）：把「一条观测能不能录」的取数与判断收在一处。
 *
 * 三道前置（缺一不可）：
 * - 任务得正在执行（IN_PROGRESS）：待执行还没开工、已完成账已冻结、已取消已销账的任务，
 *   都不再往底下录观测（唯一口径走 {@link PatrolTask#acceptsObservation()}）；
 * - 点位得是库里还在册的（撤掉/查不到的点不往上挂）；
 * - 物种得在名录里且处在启用状态（ENABLED）：编码查不到、停用掉的一律不收。
 *
 * 新录与改录（换点/换物种）都走这里，判断不再各写一遍；观测自身的字段不变量
 * （数量为正、健康状态合法、快照非空）仍由 {@link WildlifeObs} 自己守。
 */
@Service
public class ObservationGuard {

    private final PatrolTaskRepository taskRepository;
    private final MonitorSiteRepository siteRepository;
    private final SpeciesRepository speciesRepository;

    public ObservationGuard(PatrolTaskRepository taskRepository,
                            MonitorSiteRepository siteRepository,
                            SpeciesRepository speciesRepository) {
        this.taskRepository = taskRepository;
        this.siteRepository = siteRepository;
        this.speciesRepository = speciesRepository;
    }

    /** 任务必须存在且正在执行：待执行/已完成/已取消的任务都不再收新观测。 */
    public Mono<PatrolTask> requireOngoingTask(Long taskId) {
        if (taskId == null) {
            return Mono.error(new BizException("巡护任务不能为空"));
        }
        return taskRepository.findById(taskId)
                .switchIfEmpty(Mono.error(new BizException("巡护任务不存在")))
                .flatMap(task -> task.acceptsObservation()
                        ? Mono.just(task)
                        : Mono.error(new BizException("只有正在执行的巡护任务才能录入观测")));
    }

    /** 点位必须存在（已撤掉/查不到的点不往上挂）；@TableLogic 自动过滤已删除点位。 */
    public Mono<MonitorSite> requireExistingSite(Long siteId) {
        if (siteId == null) {
            return Mono.error(new BizException("监测点不能为空"));
        }
        return siteRepository.findById(siteId)
                .switchIfEmpty(Mono.error(new BizException("监测点不存在")));
    }

    /** 物种必须在名录里且启用：编码查不到/停用的都不收。返回物种（供照当前级别抄快照）。 */
    public Mono<Species> requireEnabledSpecies(String speciesCode) {
        String code = normalizeCode(speciesCode);
        if (code == null) {
            return Mono.error(new BizException("物种编码不能为空"));
        }
        return speciesRepository.findByCode(code)
                .switchIfEmpty(Mono.error(new BizException("物种不在名录里，不能录入观测")))
                .flatMap(species -> species.isEnabled()
                        ? Mono.just(species)
                        : Mono.error(new BizException("物种已在名录中停用，不能再录入观测")));
    }

    private static String normalizeCode(String speciesCode) {
        return (speciesCode == null || speciesCode.isBlank()) ? null : speciesCode.trim();
    }
}
