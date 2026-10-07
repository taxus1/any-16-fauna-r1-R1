package com.somepro.domain.report.service;

import com.somepro.common.exception.BizException;
import com.somepro.domain.obs.model.WildlifeObs;
import com.somepro.domain.obs.repository.WildlifeObsRepository;
import com.somepro.domain.report.model.AbnormalReport;
import com.somepro.domain.task.model.PatrolTask;
import com.somepro.domain.task.repository.PatrolTaskRepository;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * 异常上报受理守卫（领域服务）：把「一条上报能不能收」的取数与判断收在一处。
 *
 * 登记前置（缺一不可）：
 * - 观测得是在册的（已作废的观测报不了）；
 * - 观测的健康状态得不是正常（正常个体没什么可报的，口径走
 *   {@link WildlifeObs#isAbnormal()}）；
 * - 观测挂的那条巡护任务不能是已取消的（任务取消即销账，查不到一并拦下）。
 *
 * 类别与健康状态对口、严重程度推算仍是 {@link AbnormalReport} 的领域规则；
 * 「同一观测只挂一条未作废上报」的并发兜底仍在仓储事务内（SELECT ... FOR UPDATE）。
 */
@Service
public class ReportAdmissionGuard {

    private final WildlifeObsRepository obsRepository;
    private final PatrolTaskRepository taskRepository;

    public ReportAdmissionGuard(WildlifeObsRepository obsRepository,
                                PatrolTaskRepository taskRepository) {
        this.obsRepository = obsRepository;
        this.taskRepository = taskRepository;
    }

    /** 观测得在册、且健康状态不是正常：正常个体没什么可报，作废观测报不了。 */
    public Mono<WildlifeObs> requireReportableObs(Long obsId) {
        if (obsId == null) {
            return Mono.error(new BizException("来源观测不能为空"));
        }
        return obsRepository.findById(obsId)
                .switchIfEmpty(Mono.error(new BizException("观测记录不存在或已作废，不能上报")))
                .flatMap(obs -> obs.isAbnormal()
                        ? Mono.just(obs)
                        : Mono.error(new BizException("健康状态正常的观测不能上报异常")));
    }

    /** 观测挂的那条巡护任务不能是已取消的：取消即销账（逻辑删除），查不到的一并拦下。 */
    public Mono<PatrolTask> requireTaskNotCancelled(Long taskId) {
        return taskRepository.findById(taskId)
                .switchIfEmpty(Mono.error(new BizException("巡护任务不存在或已取消，不能上报")))
                .flatMap(task -> task.isCancelled()
                        ? Mono.error(new BizException("巡护任务已取消，不能上报"))
                        : Mono.just(task));
    }
}
