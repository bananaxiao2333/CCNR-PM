/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.pm.util;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * 时间戳 → 可读文本（纯逻辑，无 MC 依赖，可直接单测）。
 *
 * <h2>为什么单独一个类</h2>
 * 同一个工单时间要在两处显示：管理面板详情（**客户端**，按玩家所在时区）与
 * {@code /pm ticket show} 聊天输出（**服务端**，按服务端时区）。
 * 两处各写一份格式化，迟早出现「面板写着 18:30、命令写着 10:30」这种看起来像数据错乱的现象。
 *
 * <p>格式固定为 {@code yyyy-MM-dd HH:mm}：比「3 分钟前」这类相对时间更适合工单——
 * 管理员要能把它和日志、聊天记录对上时间。
 */
public final class TimeText {

    /** 显示格式（本地时区由调用方给的 ZoneId 决定）。 */
    private static final String PATTERN = "yyyy-MM-dd HH:mm";

    private TimeText() {}

    /** 用系统默认时区格式化（客户端＝玩家时区，服务端＝服务端时区）。 */
    public static String format(long epochMillis) {
        return format(epochMillis, ZoneId.systemDefault());
    }

    /**
     * 指定时区格式化。
     *
     * <p>单测靠它把结果钉死——用系统默认时区写断言会在别的机器上随机失败。
     *
     * @return {@code yyyy-MM-dd HH:mm}；{@code <= 0}（缺失/未设置）返回 {@code "-"}，
     *     而不是把 1970 年印在工单上
     */
    public static String format(long epochMillis, ZoneId zone) {
        if (epochMillis <= 0L) return "-";
        ZoneId z = zone == null ? ZoneId.systemDefault() : zone;
        return DateTimeFormatter.ofPattern(PATTERN, Locale.ROOT).withZone(z).format(Instant.ofEpochMilli(epochMillis));
    }
}
