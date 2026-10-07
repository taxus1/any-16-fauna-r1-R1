package com.somepro.application.shared;

import com.somepro.common.exception.BizException;
import reactor.core.publisher.Mono;

import java.util.function.Consumer;
import java.util.function.Function;

/**
 * 处置推进编排模板（应用层共享）：异常上报与疫病预警的「推进」用例是同一套形状 ——
 * 加载在册子单 → 留住原状态 → 领域对象校验状态机并翻状态 → 仓储按原状态条件更新
 * （并发只放行一下）→ 重查一遍（处置时刻由审计列 update_time 在落库时刷新，
 * 内存对象还是推进前的）。
 *
 * 两处之前各写一遍这份编排；以后再有同形状的状态机推进，也来调它。
 * 状态机规则本身仍在各自的领域对象里，这里只编排、不判断状态；报错文案由调用方传入。
 */
public final class AdvanceTemplate {

    private AdvanceTemplate() {
    }

    /**
     * @param id              子单 id
     * @param loadById        按 id 加载在册子单（查不到返回空 Mono）
     * @param missingMessage  子单查不到时的报错文案
     * @param currentStatus   取推进前的原状态（条件更新按它卡并发）
     * @param mutate          领域对象上的推进：校验不通过抛 {@link BizException}，并翻内存状态
     * @param flip            仓储条件更新：(新状态子单, 原状态) 翻得动返回 true，并发翻不动 false
     * @param reload          翻成功后重查（拿审计列刷新后的处置时刻）
     * @param conflictMessage 并发翻不动时的报错文案
     */
    public static <T> Mono<T> advance(Long id,
                                      Function<Long, Mono<T>> loadById,
                                      String missingMessage,
                                      Function<T, String> currentStatus,
                                      Consumer<T> mutate,
                                      Flip<T> flip,
                                      Function<T, Mono<T>> reload,
                                      String conflictMessage) {
        return loadById.apply(id)
                .switchIfEmpty(Mono.error(new BizException(missingMessage)))
                .flatMap(entity -> {
                    String fromStatus = currentStatus.apply(entity);
                    mutate.accept(entity);
                    return flip.apply(entity, fromStatus)
                            .flatMap(flipped -> flipped
                                    ? reload.apply(entity)
                                    : Mono.error(new BizException(conflictMessage)));
                });
    }

    /** 仓储侧条件更新：按原状态翻得动返回 true。 */
    @FunctionalInterface
    public interface Flip<T> {
        Mono<Boolean> apply(T entity, String fromStatus);
    }
}
