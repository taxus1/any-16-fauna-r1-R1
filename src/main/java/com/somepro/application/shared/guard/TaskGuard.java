package com.somepro.application.shared.guard;

import com.somepro.common.exception.BizException;
import com.somepro.domain.task.model.PatrolTask;
import com.somepro.domain.task.repository.PatrolTaskRepository;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * 巡护任务准入校验（应用层共享）：观测录入、异常上报都要先回源头把同一条任务捞出来
 * 看状态 —— 取数与判断全仓就这一份，不再各自重捞、各写各的判断。
 *
 * 判断在领域对象上（{@link PatrolTask#ongoing()} / {@link PatrolTask#cancelled()}），
 * 这里只管取数与按入口组织报错文案。
 */
@Component
public class TaskGuard {

    private final PatrolTaskRepository taskRepository;

    public TaskGuard(PatrolTaskRepository taskRepository) {
        this.taskRepository = taskRepository;
    }

    /** 取任务：查不到（已取消销账的 @TableLogic 也查不到）按入口文案拒绝。 */
    public Mono<PatrolTask> requireExisting(Long taskId, String nullMessage, String missingMessage) {
        if (taskId == null) {
            return Mono.error(new BizException(nullMessage));
        }
        return taskRepository.findById(taskId)
                .switchIfEmpty(Mono.error(new BizException(missingMessage)));
    }

    /** 观测录入前置：任务必须存在且正在执行（IN_PROGRESS）。 */
    public Mono<PatrolTask> requireOngoing(Long taskId) {
        return requireExisting(taskId, "巡护任务不能为空", "巡护任务不存在")
                .flatMap(task -> task.ongoing()
                        ? Mono.just(task)
                        : Mono.<PatrolTask>error(new BizException("只有正在执行的巡护任务才能录入观测")));
    }

    /** 异常上报前置：观测挂的任务不能是已取消的；取消即销账，查不到的一并拦下。 */
    public Mono<PatrolTask> requireNotCancelled(Long taskId) {
        return requireExisting(taskId, "巡护任务不能为空", "巡护任务不存在或已取消，不能上报")
                .flatMap(task -> task.cancelled()
                        ? Mono.<PatrolTask>error(new BizException("巡护任务已取消，不能上报"))
                        : Mono.just(task));
    }
}
