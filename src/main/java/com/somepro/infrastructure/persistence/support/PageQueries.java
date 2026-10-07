package com.somepro.infrastructure.persistence.support;

import com.github.pagehelper.PageHelper;
import com.somepro.domain.shared.model.PageResult;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * PageHelper 分页查询的统一收口（基础设施层共享）。
 *
 * 各仓储适配器的条件分页之前各抄一遍「startPage → selectList → 取 total → clearPage」，
 * 现在只此一份，守住硬规则：{@code PageHelper.startPage()} 后必须在 finally 里
 * {@code PageHelper.clearPage()}，否则分页参数（ThreadLocal）会污染线程池里的下一次调用。
 * 查询本身（拼 wrapper、selectList）仍在各仓储里，桥接统一走 {@link BlockingJdbc}。
 */
public final class PageQueries {

    private PageQueries() {
    }

    /**
     * @param pageNum   页码（透传，不写死）
     * @param pageSize  每页条数（透传，不写死）
     * @param select    已拼好条件的查询（startPage 之后的第一条 select 才会被分页拦截）
     * @param toDomain  PO → 领域对象转换
     */
    public static <PO, T> Mono<PageResult<T>> page(int pageNum, int pageSize,
                                                   Supplier<List<PO>> select,
                                                   Function<PO, T> toDomain) {
        return BlockingJdbc.blocking(() -> {
            try {
                PageHelper.startPage(pageNum, pageSize);
                List<PO> rows = select.get();
                long total = rows instanceof com.github.pagehelper.Page
                        ? ((com.github.pagehelper.Page<?>) rows).getTotal()
                        : rows.size();
                List<T> content = rows.stream()
                        .map(toDomain)
                        .collect(Collectors.toList());
                return new PageResult<>(content, total, pageNum, pageSize);
            } finally {
                PageHelper.clearPage();
            }
        });
    }

    /** 非空字符串判断：条件 wrapper 里拼 eq 用，各仓储口径统一。 */
    public static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
