/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.pm.ticket;

import java.util.Optional;

/**
 * 防刷闸门（纯逻辑，无 MC 依赖，可直接单测）。
 *
 * <p>为什么单独抽出来：这是整条提交链上**唯一纯服务端概念**的两条规则——
 * 客户端面板根本不知道也不该知道「冷却」与「未办结上限」的存在，
 * 因此它们没有任何「客户端已经校验过」的借口，必须由服务端独立判定。
 *
 * <p>更重要的是**边界必须被证明**：防刷规则差一位（`<` 写成 `<=`、`>=` 写成 `>`）
 * 的后果是「要么锁不住人、要么把正常玩家挡在门外」，而这两种都不会在编译期或联调时暴露。
 * 抽成纯函数后，边界可以由 {@code SubmitGateTest} 逐点钉死。
 *
 * <p>为什么复用 {@link ReportValidator.Error} 作为返回类型：它表示的是同一件事
 * ——「这次提交不被接受，原因是 X」，客户端也就能用同一套渲染逻辑显示它。
 * 再定义一个等价的错误类型只会让调用方多一次转换。
 */
public final class SubmitGate {

    /** 防刷上限；{@code 0} 一律表示「不限制」。 */
    public record Limits(int cooldownSeconds, int maxOpenPerPlayer) {
        public Limits {
            cooldownSeconds = Math.max(0, cooldownSeconds);
            maxOpenPerPlayer = Math.max(0, maxOpenPerPlayer);
        }
    }

    /**
     * 该玩家当前的提交历史（由调用方从存储层查出）。
     *
     * @param lastSubmitAtMs 上次提交时间；从未提交过传 0
     * @param unresolvedCount 当前未办结的工单数
     */
    public record History(long lastSubmitAtMs, int unresolvedCount) {}

    public static final String ERR_COOLDOWN = "ccnr_pm.report.err.cooldown";
    public static final String ERR_TOO_MANY = "ccnr_pm.report.err.too_many";

    private SubmitGate() {}

    /**
     * 判定是否放行。
     *
     * @return 拒因；通过时为空
     */
    public static Optional<ReportValidator.Error> check(Limits limits, History history, long nowMs) {
        if (limits == null || history == null) return Optional.empty();

        // 冷却：只有「限制开启」且「确实提交过」才生效。
        // lastSubmitAtMs == 0 表示从未提交——不能拿 0 去和 now 相减，否则等于把新玩家锁死。
        int cooldown = limits.cooldownSeconds();
        if (cooldown > 0 && history.lastSubmitAtMs() > 0) {
            long need = cooldown * 1000L;
            long elapsed = nowMs - history.lastSubmitAtMs();
            // elapsed < need 才算还在冷却内；正好到期（elapsed == need）必须放行
            if (elapsed >= 0 && elapsed < need) {
                long remainMs = need - elapsed;
                long remainSec = (remainMs + 999L) / 1000L; // 向上取整，避免显示「0 秒后可重试」
                return Optional.of(ReportValidator.Error.of(ERR_COOLDOWN, Long.toString(remainSec)));
            }
        }

        // 并发上限：>= 上限就拒绝（上限 3 表示「最多同时 3 条未办结」，第 4 条才该被挡）
        int maxOpen = limits.maxOpenPerPlayer();
        if (maxOpen > 0 && history.unresolvedCount() >= maxOpen) {
            return Optional.of(ReportValidator.Error.of(ERR_TOO_MANY, Integer.toString(maxOpen)));
        }

        return Optional.empty();
    }
}
