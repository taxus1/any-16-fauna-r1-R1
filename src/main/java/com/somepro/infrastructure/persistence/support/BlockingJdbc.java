package com.somepro.infrastructure.persistence.support;

import com.somepro.infrastructure.config.ReactiveOperatorContext;
import com.somepro.infrastructure.persistence.audit.AuditContextHolder;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.function.Supplier;

/**
 * 阻塞 JDBC → 响应式链路的统一桥接器（基础设施层共享组件）。
 *
 * 以前每个仓储适配器各抄一份 {@code blocking(...)}，桥接约定一旦要改（操作人传递、
 * 线程池选择）得满仓翻；现在只留这一处，所有仓储的 DB 调用都经它进响应式链路。
 *
 * 顺序不能颠倒：先 {@code deferContextual} 取 Reactor Context 里的操作人，再
 * {@code subscribeOn(boundedElastic)} —— 反过来 Callable 跑在 boundedElastic 线程上
 * 就读不到上游 Context，审计会静默退化成 system。
 */
public final class BlockingJdbc {

    private BlockingJdbc() {
    }

    /**
     * 把一次阻塞 JDBC 调用包成 Mono：切线程前先取操作人，进线程后放进
     * {@link AuditContextHolder} 供审计填充，用完即清。
     */
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
