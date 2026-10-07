package com.somepro.infrastructure.persistence.support;

import com.somepro.infrastructure.config.ReactiveOperatorContext;
import com.somepro.infrastructure.persistence.audit.AuditContextHolder;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.function.Supplier;

/**
 * 阻塞 DB 调用 → 响应式链路的统一桥接器（基础设施层共享）。
 *
 * 所有仓储适配器的 JDBC 调用都走这一个桥，不再各自抄一份：
 * 先 {@code deferContextual} 取 Reactor Context 里的操作人（顺序不能颠倒，切线程后就读不到），
 * 再切到 boundedElastic 执行 JDBC（绝不能在 Netty event-loop 上阻塞），
 * 操作人放进 {@link AuditContextHolder} 供 MetaObjectHandler 填审计列，用完即清。
 */
public final class BlockingJdbc {

    private BlockingJdbc() {
    }

    public static <T> Mono<T> blocking(Supplier<T> supplier) {
        return Mono.deferContextual(ctx -> {
            String operator = ReactiveOperatorContext.getOperator(ctx);
            return Mono.fromCallable(() -> {
                AuditContextHolder.setOperator(operator);
                try {
                    return supplier.get();
                } finally {
                    AuditContextHolder.clear();
                }
            }).subscribeOn(Schedulers.boundedElastic());
        });
    }
}
