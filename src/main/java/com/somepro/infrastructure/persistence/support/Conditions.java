package com.somepro.infrastructure.persistence.support;

/**
 * 查询条件小工具：仓储拼 wrapper 时统一用这一处判断「条件是否有值」。
 * 避免在每个仓储里各抄一份私有 {@code hasText(String)}。
 */
public final class Conditions {

    private Conditions() {
    }

    public static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
