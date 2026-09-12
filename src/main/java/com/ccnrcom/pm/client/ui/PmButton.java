/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.pm.client.ui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/**
 * 按钮的**唯一**画法（手绘，不注册为 widget）。
 *
 * <p>为什么手绘而不是用 {@code Button} widget：工单面板里按钮与下拉弹层、输入框共处一个模态体系，
 * 手绘可以精确控制「弹层打开时按钮不可点」的语义，也不必操心 {@code clearWidgets()} 之后再重新注册的生命周期问题。
 * 这与 CCNR-RP 在弹窗内使用 {@code RpButton.draw} 的做法一致。
 *
 * <p>三种变体的配色差异只体现在填充与描边，**字色规则统一**：亮底（PRIMARY）用
 * {@link PmTheme#ACCENT_TEXT}，暗底用亮字。
 */
public final class PmButton {

    /** 按钮语义变体。 */
    public enum Variant {
        /** 主操作（提交）——亮底深字。 */
        PRIMARY,
        /** 次要操作（取消、选择器）——控件底亮字。 */
        SECONDARY,
        /** 破坏性操作（驳回）——红系。 */
        DANGER
    }

    private PmButton() {}

    public static void draw(
            GuiGraphics g,
            Font font,
            int x1,
            int y1,
            int x2,
            int y2,
            String label,
            Variant variant,
            boolean hovered,
            boolean enabled) {
        core(g, font, x1, y1, x2, y2, label, variant, hovered, enabled, false, 0);
    }

    /**
     * 带**状态色**的画法：按钮文案按它对应的工单状态上色（用户要求「按钮文案也上色」）。
     *
     * <h2>亮底按钮为什么改的是描边而不是字色</h2>
     * docs/03 §2 是硬性约束：**亮底必须用深色字**（CCNR-RP 出过一次白底白字的事故）。
     * {@code PRIMARY} 是白底，若把状态色写上去，`claimed`（状态色本身就是白）会直接看不见，
     * 其它状态色的对比度也会掉到不可读。因此规则定为：**暗底用它做文字色、亮底用它做描边色**——
     * 两种底都能承载状态语义，且都不违反那条硬性约束。
     * 规则由本方法实现（亮底分支忽略传入的 {@code statusInk}），不靠调用方自觉。
     *
     * @param statusInk 该按钮对应动作的**结果状态色**（来自 {@code PmTheme.statusColor}）
     */
    public static void draw(
            GuiGraphics g,
            Font font,
            int x1,
            int y1,
            int x2,
            int y2,
            String label,
            Variant variant,
            boolean hovered,
            boolean enabled,
            int statusInk) {
        core(g, font, x1, y1, x2, y2, label, variant, hovered, enabled, true, statusInk);
    }

    /** 唯一的实现：所有按钮都从这里画，禁止另起一份（同类构件只有一种画法）。 */
    private static void core(
            GuiGraphics g,
            Font font,
            int x1,
            int y1,
            int x2,
            int y2,
            String label,
            Variant variant,
            boolean hovered,
            boolean enabled,
            boolean tinted,
            int statusInk) {
        int fill;
        int border;
        int ink;

        switch (variant) {
            case PRIMARY -> {
                fill = hovered && enabled ? PmTheme.ACCENT_FILL_HOVER : PmTheme.ACCENT_FILL;
                border = hovered && enabled ? PmTheme.ACCENT_HOVER : PmTheme.CYAN;
                ink = PmTheme.ACCENT_TEXT; // 亮底必须深字
                if (tinted) border = statusInk; // 状态色改走描边（见上面的方法注释）
            }
            case DANGER -> {
                fill = hovered && enabled ? PmTheme.RED_BG_HOVER : PmTheme.RED_BG_SOFT;
                border = hovered && enabled ? PmTheme.DANGER_HOVER : PmTheme.DANGER;
                ink = enabled ? (tinted ? statusInk : PmTheme.RED_LINE) : PmTheme.TEXT_DISABLED;
            }
            default -> {
                fill = hovered && enabled ? PmTheme.SURFACE_CONTROL_HOVER : PmTheme.SURFACE_CONTROL;
                border = hovered && enabled ? PmTheme.PANEL_BORDER_BRIGHT : PmTheme.PANEL_BORDER;
                ink = enabled ? (tinted ? statusInk : PmTheme.TEXT_PRIMARY) : PmTheme.TEXT_DISABLED;
            }
        }

        g.fill(x1, y1, x2, y2, fill);
        PmTheme.outlined(g, x1, y1, x2, y2, border);

        String text = PmTheme.clip(font, label, (x2 - x1) - 6);
        int tx = x1 + ((x2 - x1) - font.width(text)) / 2;
        int ty = y1 + ((y2 - y1) - 8) / 2;
        g.drawString(font, text, tx, ty, ink, false);
    }

    /** 命中测试。 */
    public static boolean hit(double mx, double my, int x1, int y1, int x2, int y2) {
        return mx >= x1 && mx < x2 && my >= y1 && my < y2;
    }
}
