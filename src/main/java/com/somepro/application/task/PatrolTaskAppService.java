package com.somepro.application.task;

import com.somepro.common.exception.BizException;
import com.somepro.application.shared.guard.SiteGuard;
import com.somepro.application.shared.guard.StationGuard;
import com.somepro.domain.obs.model.ObsSummary;
import com.somepro.domain.obs.repository.WildlifeObsRepository;
import com.somepro.domain.shared.model.PageResult;
import com.somepro.domain.task.model.PatrolTask;
import com.somepro.domain.task.repository.PatrolTaskRepository;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.LocalDate;

/**
 * 巡护任务应用层：编排巡护任务用例（派发、修改、详情、开工、完成回报、取消、条件分页）。
 *
 * 派发门槛（看两头）：
 * - 站得是在运行的（ACTIVE）：停用/关闭的站不再往那儿派；
 * - 点得是在册的（ACTIVE）：停测/撤掉的点不再往那儿派；
 * - 同一个点同一天只挂一条还没走完的任务：前面那条待执行/执行中就先别派，
 *   等它完了或者撤了再派；已取消的不占位，那天能重派。
 * 「站存在且在运行」「点存在且在册」的取数+判断统一走 {@link StationGuard}/{@link SiteGuard}，
 * 与点位模块共用同一份口径；改任务换站/换点/改计划日期同样过这三道校验（占位排除自己）。
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
    private final StationGuard stationGuard;
    private final SiteGuard siteGuard;

    public PatrolTaskAppService(PatrolTaskRepository taskRepository,
                                WildlifeObsRepository obsRepository,
                                StationGuard stationGuard,
                                SiteGuard siteGuard) {
        this.taskRepository = taskRepository;
        this.obsRepository = obsRepository;
        this.stationGuard = stationGuard;
        this.siteGuard = siteGuard;
    }

    /** 派发巡护任务：默认待执行；taskNo 留空时由仓储层按 PT-YYYY-NNNN 生成。 */
    public Mono<PatrolTask> dispatch(String taskNo, Long stationId, Long siteId,
                                     String patrolType, LocalDate plannedDate, String executor) {
        return stationGuard.requireOperating(stationId, "所属监测站不能为空", "所属监测站不存在", "派发巡护任务")
                .then(siteGuard.requireActive(siteId, "监测点不能为空", "监测点不存在",
                        "监测点已停测，不能派发巡护任务"))
                .then(Mono.defer(() -> {
                    PatrolTask task = PatrolTask.create(stationId, siteId, patrolType, plannedDate, executor);
                    task.setTaskNo(normalizeNo(taskNo));
                    return requireSiteDateFree(siteId, task.getPlannedDate(), null)
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
        return requireExistingTask(id)
                .flatMap(task -> {
                    Mono<Void> stationCheck = (stationId != null && !stationId.equals(task.getStationId()))
                            ? stationGuard.requireOperating(stationId, "所属监测站不能为空",
                                    "所属监测站不存在", "派发巡护任务").then()
                            : Mono.empty();
                    Mono<Void> siteCheck = (siteId != null && !siteId.equals(task.getSiteId()))
                            ? siteGuard.requireActive(siteId, "监测点不能为空", "监测点不存在",
                                    "监测点已停测，不能派发巡护任务").then()
                            : Mono.empty();
                    return Mono.when(stationCheck, siteCheck)
                            .then(Mono.defer(() -> {
                                task.updateProfile(stationId, siteId, patrolType, plannedDate, executor);
                                return requireSiteDateFree(task.getSiteId(), task.getPlannedDate(), task.getId())
                                        .then(taskRepository.update(task));
                            }));
                });
    }

    public Mono<PatrolTask> detail(Long id) {
        return requireExistingTask(id);
    }

    /** 取消：状态置已取消并销账（逻辑删除），名单里不再翻出来，账留在表里。 */
    public Mono<Void> cancel(Long id) {
        return requireExistingTask(id)
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
        return requireExistingTask(id)
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
        return requireExistingTask(id)
                .flatMap(task -> obsRepository.summarizeByTaskId(task.getId())
                        .flatMap((ObsSummary summary) -> {
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

    /** 取在册任务：取消即销账（@TableLogic），查不到的各入口一律按「不存在」拒绝。 */
    private Mono<PatrolTask> requireExistingTask(Long id) {
        return taskRepository.findById(id)
                .switchIfEmpty(Mono.error(new BizException("巡护任务不存在")));
    }

    /** 同点同日占位校验：已有没走完的任务（待执行/执行中）就先别派。 */
    private Mono<Void> requireSiteDateFree(Long siteId, LocalDate plannedDate, Long excludeId) {
        return taskRepository.countUnfinishedBySiteAndDate(siteId, plannedDate, excludeId)
                .flatMap(count -> count > 0
                        ? Mono.<Void>error(new BizException("该监测点当天已有未完成的巡护任务，待其完成或取消后再派"))
                        : Mono.<Void>empty());
    }

    private static String normalizeNo(String taskNo) {
        return (taskNo == null || taskNo.isBlank()) ? null : taskNo.trim();
    }
}
