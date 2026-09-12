/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.pm.ticket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 防刷闸门的边界测试。
 *
 * <p>防刷规则差一位的后果是「锁不住人」或「把正常玩家挡在门外」，两者都不会在编译期
 * 或一次手工联调里暴露。这里把每条边界**逐点**钉死。
 */
class SubmitGateTest {

    private static final long NOW = 1_700_000_000_000L;

    private static Optional<ReportValidator.Error> check(int cooldownSec, int maxOpen, long lastAt, int unresolved) {
        return SubmitGate.check(
                new SubmitGate.Limits(cooldownSec, maxOpen), new SubmitGate.History(lastAt, unresolved), NOW);
    }

    // ------------------------------------------------------------------
    // 冷却
    // ------------------------------------------------------------------

    @Test
    @DisplayName("冷却中：拒绝，并回传向上取整的剩余秒数")
    void withinCooldownRejected() {
        // 冷却 60 秒，刚过 10 秒 → 还应等 50 秒
        Optional<ReportValidator.Error> e = check(60, 0, NOW - 10_000L, 0);
        assertTrue(e.isPresent());
        assertEquals(SubmitGate.ERR_COOLDOWN, e.get().key());
        assertEquals(java.util.List.of("50"), e.get().args());
    }

    @Test
    @DisplayName("剩余不足 1 秒也必须显示 1 秒，不能显示 0（会让人以为可以立刻重试）")
    void remainingSecondsRoundedUp() {
        // 还剩 1ms → 向上取整为 1 秒
        assertEquals(
                java.util.List.of("1"),
                check(60, 0, NOW - 59_999L, 0).orElseThrow().args());
        // 还剩 1500ms → 2 秒
        assertEquals(
                java.util.List.of("2"),
                check(60, 0, NOW - 58_500L, 0).orElseThrow().args());
    }

    @Test
    @DisplayName("冷却正好到期必须放行（边界不多不少）")
    void exactlyExpiredAllowed() {
        assertTrue(check(60, 0, NOW - 60_000L, 0).isEmpty(), "elapsed == need 时应放行");
        assertTrue(check(60, 0, NOW - 60_001L, 0).isEmpty(), "已超过冷却更应放行");
    }

    @Test
    @DisplayName("冷却还差 1 毫秒仍然要拒绝")
    void oneMillisecondBeforeStillRejected() {
        assertTrue(check(60, 0, NOW - 59_999L, 0).isPresent());
    }

    @Test
    @DisplayName("从未提交过（lastAt = 0）不受冷却限制——否则等于把所有新玩家锁死")
    void neverSubmittedNotCooldown() {
        assertTrue(check(60, 0, 0L, 0).isEmpty());
    }

    @Test
    @DisplayName("冷却配为 0 表示不限制")
    void zeroCooldownMeansUnlimited() {
        assertTrue(check(0, 0, NOW - 1L, 0).isEmpty(), "刚提交完也应放行");
    }

    @Test
    @DisplayName("时钟回拨（lastAt 在未来）不应把玩家永久锁死")
    void clockSkewDoesNotLockOut() {
        // elapsed 为负时不判冷却：宁可少拦一次，也不要因为时钟问题把人挡住
        assertTrue(check(60, 0, NOW + 120_000L, 0).isEmpty());
    }

    // ------------------------------------------------------------------
    // 未办结并发上限
    // ------------------------------------------------------------------

    @Test
    @DisplayName("达到上限时拒绝；差一条时放行（上限是「最多同时几条」）")
    void maxOpenBoundary() {
        assertTrue(check(0, 3, 0L, 2).isEmpty(), "2 < 3 应放行");
        assertTrue(check(0, 3, 0L, 3).isPresent(), "3 == 3 应拒绝");
        assertTrue(check(0, 3, 0L, 4).isPresent(), "超过上限当然拒绝");
    }

    @Test
    @DisplayName("上限配为 0 表示不限制（无论已有多少条）")
    void zeroMaxOpenMeansUnlimited() {
        assertTrue(check(0, 0, 0L, 999).isEmpty());
    }

    @Test
    @DisplayName("拒绝时回传上限值，便于提示文案显示具体数字")
    void maxOpenReportsLimit() {
        ReportValidator.Error e = check(0, 3, 0L, 5).orElseThrow();
        assertEquals(SubmitGate.ERR_TOO_MANY, e.key());
        assertEquals(java.util.List.of("3"), e.args());
    }

    // ------------------------------------------------------------------
    // 组合与负数防御
    // ------------------------------------------------------------------

    @Test
    @DisplayName("冷却先于并发判定（同时触发时提示「稍后再试」比「工单太多」更可操作）")
    void cooldownCheckedFirst() {
        Optional<ReportValidator.Error> e = check(60, 3, NOW - 1_000L, 5);
        assertEquals(SubmitGate.ERR_COOLDOWN, e.orElseThrow().key());
    }

    @Test
    @DisplayName("负数配置被规整为「不限制」，不会出现「负上限把所有人挡住」")
    void negativeLimitsNormalised() {
        SubmitGate.Limits l = new SubmitGate.Limits(-5, -1);
        assertEquals(0, l.cooldownSeconds());
        assertEquals(0, l.maxOpenPerPlayer());
        assertTrue(SubmitGate.check(l, new SubmitGate.History(NOW, 100), NOW).isEmpty());
    }

    @Test
    @DisplayName("两个限制都关闭时一律放行")
    void allDisabledAllowsEverything() {
        assertTrue(check(0, 0, NOW, 100).isEmpty());
    }
}
