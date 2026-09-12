/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.pm.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 时间文案门禁。
 *
 * <p>用户反馈「查看工单那里不显示提交时间」——数据一直在（{@code TicketPage.Entry.createdAt}），
 * 只是没人画它。补上之后，格式与「缺失值怎么显示」必须有测试钉住：
 * 少一个时间字段就会把 1970 年印在工单上，那比不显示更让人困惑。
 */
class TimeTextTest {

    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

    private static long millisOf(int year, int month, int day, int hour, int minute, ZoneId zone) {
        return ZonedDateTime.of(year, month, day, hour, minute, 0, 0, zone)
                .toInstant()
                .toEpochMilli();
    }

    @Test
    @DisplayName("按给定时区格式化为 yyyy-MM-dd HH:mm")
    void formatsToMinute() {
        long t = millisOf(2026, 9, 12, 18, 30, SHANGHAI);
        assertEquals("2026-09-12 18:30", TimeText.format(t, SHANGHAI));
    }

    @Test
    @DisplayName("时区不同 → 文本不同（这正是必须由调用方决定时区的原因）")
    void timezoneMatters() {
        long t = millisOf(2026, 9, 12, 18, 30, SHANGHAI);
        assertEquals("2026-09-12 10:30", TimeText.format(t, ZoneId.of("UTC")));
        assertFalse(TimeText.format(t, SHANGHAI).equals(TimeText.format(t, ZoneId.of("UTC"))));
    }

    @Test
    @DisplayName("缺失时间戳（0/负数）显示 -，不显示 1970")
    void missingValueIsDash() {
        assertEquals("-", TimeText.format(0L, SHANGHAI));
        assertEquals("-", TimeText.format(-1L, SHANGHAI));
        assertEquals("-", TimeText.format(0L));
    }

    @Test
    @DisplayName("秒被截掉（工单精度到分钟就够，多出来只会挤占行宽）")
    void secondsAreDropped() {
        long t = millisOf(2026, 1, 2, 3, 4, SHANGHAI) + 59_000L;
        assertEquals("2026-01-02 03:04", TimeText.format(t, SHANGHAI));
    }

    @Test
    @DisplayName("null 时区回退到系统默认，不抛异常")
    void nullZoneFallsBack() {
        long t = millisOf(2026, 9, 12, 18, 30, ZoneId.systemDefault());
        assertEquals("2026-09-12 18:30", TimeText.format(t, null));
    }
}
