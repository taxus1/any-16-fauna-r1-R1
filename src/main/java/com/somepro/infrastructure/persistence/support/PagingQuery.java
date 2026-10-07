package com.somepro.infrastructure.persistence.support;

import com.github.pagehelper.PageHelper;
import com.somepro.domain.shared.model.PageResult;

import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * PageHelper 分页查询的统一收口（基础设施层共享组件）。
 *
 * 以前每个仓储适配器各抄一份「startPage → selectList → 取 total → 转领域 →
 * finally clearPage」的分页块，八处几乎逐字相同，清漏一次就污染线程池里下一次调用。
 * 这里把这套固定动作收成一处，仓储只负责提供查询与 PO→领域的转换。
 */
public final class PagingQuery {

    private PagingQuery() {
    }

    /**
     * 在当前线程上挂上 PageHelper 分页参数后执行查询，并把 PO 行转换成领域分页结果。
     *
     * @param pageNum   页码（1 起，透传，不写死）
     * @param pageSize  每页条数（透传，不写死）
     * @param query     已拼好条件的列表查询（PageHelper 靠 ThreadLocal 拦截 SQL 并补 count）
     * @param converter PO → 领域对象的转换
     */
    public static <PO, T> PageResult<T> page(int pageNum, int pageSize,
                                             Supplier<List<PO>> query,
                                             Function<PO, T> converter) {
        try {
            PageHelper.startPage(pageNum, pageSize);
            List<PO> rows = query.get();
            // 命中分页插件时返回的是 com.github.pagehelper.Page，可直接取总数
            long total = rows instanceof com.github.pagehelper.Page
                    ? ((com.github.pagehelper.Page<?>) rows).getTotal()
                    : rows.size();
            List<T> content = rows.stream()
                    .map(converter)
                    .collect(Collectors.toList());
            return new PageResult<>(content, total, pageNum, pageSize);
        } finally {
            // 分页参数靠 ThreadLocal 传递，必须清理，否则污染线程池里的下一次调用
            PageHelper.clearPage();
        }
    }
}
