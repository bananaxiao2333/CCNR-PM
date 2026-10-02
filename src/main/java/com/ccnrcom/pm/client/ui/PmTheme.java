/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.pm.client.ui;

import java.util.List;
import java.util.function.ToIntFunction;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/**
 * 「CCNR:NET 机密终端」风格令牌与共享绘制入口。
 *
 * <p>令牌数值与 CCNR-RP 的 {@code RpTheme} 保持一致，这样两个模组的界面并排出现时是同一套视觉语言
 * （黑白灰军用终端；仅告警用红）。本类**不引用** CCNR-RP，是独立的等价实现。
 *
 * <p>硬性约束（沿用 CCNR 系列规范）：**任何亮色/白色填充之上的文字必须用 {@link #ACCENT_TEXT}**，
 * 否则会出现白底白字。新增构件时先问三句：①这个底是亮色吗 ②字色是深色吗 ③深色字只出现在亮底分支吗。
 *
 * <p>共享入口规则：同类构件只能走本类的方法（{@code terminalPanel}/{@code listRow}/{@code popupPanel}/
 * {@code modalScrim} …），**禁止在界面代码里直接写 {@code 0x} 裸色值**。
 */
public final class PmTheme {

    // ---- 形状 ----
    public static final float RADIUS_MEDIUM = 8f;

    public static final float RADIUS_LARGE = 14f;

    // ---- 面板 / 表面 ----
    public static final int BG_DEEP = 0xCC0A0A0A;
    public static final int PANEL_BG = 0x99141414;
    public static final int PANEL_BG_EVEN = 0x9B161616;
    public static final int PANEL_BG_ALT = 0xB3222222;
    public static final int OVERLAY = 0xB3141414;
    public static final int SURFACE_POPUP = 0xE0323232;
    public static final int SURFACE_CONTROL = 0xA8323232;
    public static final int SURFACE_CONTROL_HOVER = 0xD03A3A3A;
    public static final int SURFACE_INSET = 0xB0383838;
    public static final int SURFACE_SUNKEN = 0xFF202020;

    // ---- 描边 ----
    public static final int PANEL_BORDER = 0xFF333333;
    public static final int PANEL_BORDER_BRIGHT = 0xFF666666;

    // ---- 遮罩 ----
    public static final int SCRIM = 0xA6000000;
    public static final int TRANSPARENT = 0x00000000;
    public static final int BLACK = 0xFF000000;

    // ---- 滚动条 ----
    public static final int SCROLL_TRACK = 0x40383838;
    public static final int SCROLL_TRACK_ACTIVE = 0x80383838;

    // ---- 文本 ----
    public static final int TEXT_PRIMARY = 0xFFFFFFFF;
    public static final int TEXT_BRIGHT = 0xFFB4B4B4;
    public static final int TEXT_SECONDARY = 0xFFA0A0A0;
    public static final int TEXT_DIM = 0xFF666666;
    public static final int TEXT_DISABLED = 0xFF6A6A6A;

    // ---- 强调（本调色板里"强调"= 亮色填充 + 深色字）----
    public static final int CYAN = 0xFFFFFFFF;
    public static final int CYAN_DIM = 0xFF888888;
    public static final int ACCENT = CYAN_DIM;
    public static final int ACCENT_HOVER = CYAN;
    /** 亮底之上唯一允许的墨色。 */
    public static final int ACCENT_TEXT = 0xFF111111;

    public static final int ACCENT_FILL = CYAN;
    public static final int ACCENT_FILL_HOVER = 0xFFDADADA;

    // ---- 告警 ----
    public static final int RED = 0xFFFF3B30;
    public static final int RED_DIM = 0xFF8C2320;
    public static final int RED_LINE = 0xFFFF4A40;
    public static final int RED_BG_SOFT = 0xAE150C0C;
    public static final int RED_BG_HOVER = 0xD0641613;
    public static final int DANGER = RED_DIM;
    public static final int DANGER_HOVER = RED;

    // ---- 状态色 ----
    public static final int STATUS_OPEN = 0xFFFF3B30;
    public static final int STATUS_CLAIMED = 0xFFFFFFFF;
    public static final int STATUS_CLOSED = 0xFF7E8A8F;
    public static final int STATUS_REJECTED = 0xFF666666;

    public static final int SCANLINE = 0x05FFFFFF;

    private PmTheme() {}

    /** 给 {@code 0xRRGGBB} 套一个透明度。 */
    public static int alphaBlend(int rgb, int alpha) {
        return (alpha << 24) | (rgb & 0xFFFFFF);
    }

    /** 工单状态色（按持久化小写字面量）。 */
    public static int statusColor(String statusId) {
        if (statusId == null) return STATUS_REJECTED;
        return switch (statusId) {
            case "open" -> STATUS_OPEN;
            case "claimed" -> STATUS_CLAIMED;
            case "closed" -> STATUS_CLOSED;
            default -> STATUS_REJECTED;
        };
    }

    /**
     * 对局事件状态色（按 CCNR-RP 的 {@code EventState} 枚举名）。
     *
     * <p>与 {@link #statusColor} 分开而不是塞进同一个 switch：两者是**不同的枚举**
     * （{@code SCHEDULED/RUNNING/SETTLED} 与 {@code open/claimed/closed/rejected}），
     * 合并的唯一后果是把一个域的取值喂给另一个域时静默命中 {@code default} 而不报警。
     */
    public static int eventStateColor(String state) {
        if (state == null) return TEXT_DISABLED;
        return switch (state) {
            case "RUNNING" -> STATUS_CLAIMED; // 亮白：正在发生
            case "SETTLED" -> STATUS_CLOSED; // 灰蓝：已收束
            default -> TEXT_DISABLED; // SCHEDULED：待触发
        };
    }

    // ------------------------------------------------------------------
    // 文本
    // ------------------------------------------------------------------

    public static List<String> wrapText(Font font, String text, int maxWidth) {
        return PmTextLayout.wrapText(text, maxWidth, font::width);
    }

    /** 纯逻辑重载（单测用）。 */
    public static List<String> wrapText(String text, int maxWidth, ToIntFunction<String> width) {
        return PmTextLayout.wrapText(text, maxWidth, width);
    }

    public static String clip(Font font, String s, int maxWidth) {
        return PmTextLayout.clip(s, maxWidth, font::width);
    }

    /** 纯逻辑重载（单测用）。 */
    public static String clip(String s, int maxWidth, ToIntFunction<String> width) {
        return PmTextLayout.clip(s, maxWidth, width);
    }

    /** 终端标签：{@code [ NAV ]}。结构性标签保留英文，是这套视觉语言的一部分。 */
    public static String tag(String s) {
        return "[ " + s + " ]";
    }

    /** 终端小节标题：{@code // PROFILE}。 */
    public static String section(String s) {
        return "// " + s;
    }

    // ------------------------------------------------------------------
    // 共享绘制入口
    // ------------------------------------------------------------------

    /** 嵌入式面板：半透明底 + 1px 描边。 */
    public static void terminalPanel(GuiGraphics g, int x1, int y1, int x2, int y2, float radius) {
        g.fill(x1, y1, x2, y2, OVERLAY);
        outlined(g, x1, y1, x2, y2, PANEL_BORDER);
    }

    /** 顶层面板：terminalPanel + 四角 L 形角标 + 顶部高光轨。 */
    public static void terminalFrame(GuiGraphics g, int x1, int y1, int x2, int y2, float radius) {
        terminalPanel(g, x1, y1, x2, y2, radius);
        int len = Math.max(6, Math.min(14, (x2 - x1) / 64));
        cornerBrackets(g, x1, y1, x2, y2, len, PANEL_BORDER_BRIGHT);
        g.fill(x1 + 1, y1, x2 - 1, y1 + 1, alphaBlend(PANEL_BORDER_BRIGHT, 0x2E));
    }

    public static void card(GuiGraphics g, int x1, int y1, int x2, int y2, float radius, int bg) {
        g.fill(x1, y1, x2, y2, bg);
        outlined(g, x1, y1, x2, y2, PANEL_BORDER);
    }

    /** 目录面板：列表容器底。 */
    public static void listPanel(GuiGraphics g, int x1, int y1, int x2, int y2) {
        g.fill(x1, y1, x2, y2, PANEL_BG_EVEN);
        outlined(g, x1, y1, x2, y2, PANEL_BORDER);
    }

    public static void cornerBrackets(GuiGraphics g, int x1, int y1, int x2, int y2, int len, int color) {
        // 左上
        g.fill(x1, y1, x1 + len, y1 + 1, color);
        g.fill(x1, y1, x1 + 1, y1 + len, color);
        // 右上
        g.fill(x2 - len, y1, x2, y1 + 1, color);
        g.fill(x2 - 1, y1, x2, y1 + len, color);
        // 左下
        g.fill(x1, y2 - 1, x1 + len, y2, color);
        g.fill(x1, y2 - len, x1 + 1, y2, color);
        // 右下
        g.fill(x2 - len, y2 - 1, x2, y2, color);
        g.fill(x2 - 1, y2 - len, x2, y2, color);
    }

    public static void gridOverlay(GuiGraphics g, int x1, int y1, int x2, int y2) {
        int c = alphaBlend(PANEL_BORDER, 0x0F);
        for (int x = x1 + 32; x < x2; x += 32) g.fill(x, y1, x + 1, y2, c);
        for (int y = y1 + 32; y < y2; y += 32) g.fill(x1, y, x2, y + 1, c);
    }

    public static void scanlines(GuiGraphics g, int x1, int y1, int x2, int y2) {
        for (int y = y1; y < y2; y += 3) g.fill(x1, y, x2, y + 1, SCANLINE);
    }

    /** 输入/下拉控件框。 */
    public static void controlBox(GuiGraphics g, int x1, int y1, int x2, int y2, boolean active) {
        g.fill(x1, y1, x2, y2, SURFACE_CONTROL);
        outlined(g, x1, y1, x2, y2, active ? PANEL_BORDER_BRIGHT : PANEL_BORDER);
    }

    /** 行底色（纯函数：悬停 > 奇偶条纹）。 */
    public static int rowColor(int index, boolean hovered) {
        if (hovered) return PANEL_BG_ALT;
        return (index & 1) == 0 ? PANEL_BG : TRANSPARENT;
    }

    public static void listRow(GuiGraphics g, int x1, int y1, int x2, int y2, int index, boolean hovered) {
        g.fill(x1, y1, x2, y2, rowColor(index, hovered));
    }

    public static void listHeaderRule(GuiGraphics g, int x1, int x2, int y) {
        g.fill(x1, y, x2, y + 1, PANEL_BORDER);
    }

    /** 选中行：亮底深字（调用方必须用 {@link #ACCENT_TEXT} 画字）。 */
    public static void selectedBar(GuiGraphics g, int x1, int y1, int x2, int y2, float radius) {
        g.fill(x1, y1, x2, y2, CYAN);
        g.fill(x1, y1, x1 + 3, y2, TEXT_PRIMARY);
        g.fill(x1, y2 - 1, x2, y2, TEXT_BRIGHT);
    }

    /** 弹层底。 */
    public static void popupPanel(GuiGraphics g, int x1, int y1, int x2, int y2) {
        g.fill(x1, y1, x2, y2, SURFACE_POPUP);
        outlined(g, x1, y1, x2, y2, PANEL_BORDER_BRIGHT);
        g.fill(x1 + 1, y1, x2 - 1, y1 + 1, alphaBlend(PANEL_BORDER_BRIGHT, 0x2E));
    }

    public static void popupRow(GuiGraphics g, int x1, int y1, int x2, int y2, boolean current, boolean hovered) {
        if (current) {
            g.fill(x1, y1, x2, y2, CYAN);
        } else if (hovered) {
            g.fill(x1, y1, x2, y2, alphaBlend(CYAN, 0x1F));
        }
    }

    /** 弹层行文字色（亮底必须深字）。 */
    public static int popupRowText(boolean current, boolean hovered) {
        if (current) return ACCENT_TEXT;
        return hovered ? TEXT_PRIMARY : TEXT_BRIGHT;
    }

    public static void modalScrim(GuiGraphics g, int width, int height) {
        g.fill(0, 0, width, height, SCRIM);
    }

    /** 1px 描边（四个边分别 fill，避免依赖圆角渲染后端）。 */
    public static void outlined(GuiGraphics g, int x1, int y1, int x2, int y2, int color) {
        g.fill(x1, y1, x2, y1 + 1, color);
        g.fill(x1, y2 - 1, x2, y2, color);
        g.fill(x1, y1 + 1, x1 + 1, y2 - 1, color);
        g.fill(x2 - 1, y1 + 1, x2, y2 - 1, color);
    }
}
