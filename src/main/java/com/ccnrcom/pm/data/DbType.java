/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.pm.data;

import java.util.Locale;

/** 数据库后端类型。 */
public enum DbType {
    SQLITE,
    MYSQL;

    /** 解析配置字面量；未知值返回 {@code null}（由调用方决定回退策略并记日志）。 */
    public static DbType parse(String raw) {
        if (raw == null) return null;
        String s = raw.trim().toLowerCase(Locale.ROOT);
        if (s.isEmpty()) return null;
        try {
            return valueOf(s.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
