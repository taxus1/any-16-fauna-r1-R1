package com.somepro.application.task;

import com.somepro.common.exception.BizException;
import com.somepro.domain.obs.repository.WildlifeObsRepository;
import com.somepro.domain.shared.model.PageResult;
import com.somepro.domain.task.model.PatrolTask;
import com.somepro.domain.task.repository.PatrolTaskRepository;
import com.somepro.domain.task.service.PatrolDispatchGuard;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.LocalDate;

/**
 * 巡护任务应用层：编排巡护任务用例（派发、修改、详情、开工、完成回报、取消、条件分页）。
 *
 * 「能不能派」（站在运行、点在册、同点同日不撞未完成任务）的取数与判断统一收在
 * {@link PatrolDispatchGuard}，新派与改任务都调它，本类只做用例编排。
 *
 * 执行环节：
 * - 开工只开得动待执行的任务；完成回报只回报得了执行中的任务，没开工不能直接报完成；
 * - 完成回报时把任务名下的观测账（总条数、异常条数）从观测记录里数清写回，
 *   与观测录入读同一张表，两边数字对得上；已完成的任务不再收新观测，补录得另开任务。
 */
@Service
public class PatrolTaskAppService {

    private final PatrolTaskRepository taskRepository;
    private final WildlifeObsRepository obsRepository;
    private final PatrolDispatchGuard dispatchGuard;

    public PatrolTaskAppService(PatrolTaskRepository taskRepository,
                                WildlifeObsRepository obsRepository,
                                PatrolDispatchGuard dispatchGuard) {
        this.taskRepository = taskRepository;
        this.obsRepository = obsRepository;
        this.dispatchGuard = dispatchGuard;
    }

    /** 派发巡护任务：默认待执行；taskNo 留空时由仓储层按 PT-YYYY-NNNN 生成。 */
    public Mono<PatrolTask> dispatch(String taskNo, Long stationId, Long siteId,
                                     String patrolType, LocalDate plannedDate, String executor) {
        return dispatchGuard.requireDispatchableStation(stationId)
                .then(dispatchGuard.requireDispatchableSite(siteId))
                .then(Mono.defer(() -> {
                    PatrolTask task = PatrolTask.create(stationId, siteId, patrolType, plannedDate, executor);
                    task.setTaskNo(normalizeNo(taskNo));
                    return dispatchGuard.requireSiteDateFree(siteId, task.getPlannedDate(), null)
                            .then(taskRepository.create(task));
                }));
    }

    /**
     * 改任务：站/点/类型/计划日期/执行人传啥改啥。
     * 换站要过「站在运行」校验，换点要过「点在册」校验，
     * 改完后的（点, 计划日期）组合不能撞上别的未完成任务（排除自己）。
     */
    public Mono<PatrolTask> updateTask(Long id, Long stationId, Long siteId, String patrolType,
                                       LocalDate plannedDate, String executor) {
        return taskRepository.findById(id)
                .switchIfEmpty(Mono.error(new BizException("巡护任务不存在")))
                .flatMap(task -> {
                    Mono<Void> stationCheck = (stationId != null && !stationId.equals(task.getStationId()))
                            ? dispatchGuard.requireDispatchableStation(stationId).then()
                            : Mono.empty();
                    Mono<Void> siteCheck = (siteId != null && !siteId.equals(task.getSiteId()))
                            ? dispatchGuard.requireDispatchableSite(siteId).then()
                            : Mono.empty();
                    return Mono.when(stationCheck, siteCheck)
                            .then(Mono.defer(() -> {
                                task.updateProfile(stationId, siteId, patrolType, plannedDate, executor);
                                return dispatchGuard.requireSiteDateFree(task.getSiteId(), task.getPlannedDate(), task.getId())
                                        .then(taskRepository.update(task));
                            }));
                });
    }

    public Mono<PatrolTask> detail(Long id) {
        return taskRepository.findById(id)
                .switchIfEmpty(Mono.error(new BizException("巡护任务不存在")));
    }

    /** 取消：状态置已取消并销账（逻辑删除），名单里不再翻出来，账留在表里。 */
    public Mono<Void> cancel(Long id) {
        return taskRepository.findById(id)
                .switchIfEmpty(Mono.error(new BizException("巡护任务不存在")))
                .flatMap(task -> {
                    task.cancel();
                    return taskRepository.cancel(task);
                });
    }

    /**
     * 开工：待执行 -> 执行中，记下开工时刻。
     * 已开工/已完成/已取消的别重复开（领域对象拦下）；并发点两下由条件更新兜底，
     * 只有一下翻得动，另一下报状态已变化，开工时刻不会被改来改去。
     */
    public Mono<PatrolTask> start(Long id) {
        return taskRepository.findById(id)
                .switchIfEmpty(Mono.error(new BizException("巡护任务不存在")))
                .flatMap(task -> {
                    task.start();
                    return taskRepository.start(task)
                            .flatMap(flipped -> flipped
                                    ? Mono.just(task)
                                    : Mono.error(new BizException("任务状态已变化，请刷新后重试")));
                });
    }

    /**
     * 完成回报：执行中 -> 已完成，记下完成时刻，并把这一趟的观测账归拢写回。
     * 账从观测记录里按任务数清（总条数、异常条数），与观测录入对得上；
     * 还没开工的直接报完成不行，已完成的重复回报也不会二次计数、不挪完成时刻。
     */
    public Mono<PatrolTask> complete(Long id) {
        return taskRepository.findById(id)
                .switchIfEmpty(Mono.error(new BizException("巡护任务不存在")))
                .flatMap(task -> obsRepository.summarizeByTaskId(task.getId())
                        .flatMap(summary -> {
                            task.complete(summary.obsCount(), summary.abnormalCount());
                            return taskRepository.complete(task)
                                    .flatMap(flipped -> flipped
                                            ? Mono.just(task)
                                            : Mono.error(new BizException("任务状态已变化，请刷新后重试")));
                        }));
    }

    /** 条件分页：站/点/类型/状态/计划日期随意拼，全空翻整份任务。 */
    public Mono<PageResult<PatrolTask>> pageTasks(int pageNum, int pageSize,
                                                  Long stationId, Long siteId, String patrolType,
                                                  String status, LocalDate plannedDate) {
        return taskRepository.page(pageNum, pageSize, stationId, siteId, patrolType, status, plannedDate);
    }

    private static String normalizeNo(String taskNo) {
        return (taskNo == null || taskNo.isBlank()) ? null : taskNo.trim();
    }
}
