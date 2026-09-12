/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.pm.client.ui;

import net.minecraft.client.gui.GuiGraphics;

/**
 * 纵向滚动条（全局静态状态：同一时刻只允许拖一条）。
 *
 * <p>契约（沿用 CCNR-RP docs/14 §5.7）：任何内容溢出的区域都必须**既能滚轮、也能拖拽**，
 * 并且画出一条滚动条；不溢出时只画一条低透明度轨道（不占内容宽度）。
 *
 * <p>为什么拖拽状态放静态字段而不是每个实例一份：{@code Screen} 每次 {@code init()} 都会重建，
 * 实例字段会让「按住拖动时窗口 resize」直接丢状态。静态字段由 {@link #endDrag()} 与 id 校验共同约束，
 * 不会串到别的列表上（id 不匹配一律返回 -1）。
 */
public final class PmScrollbar {

    /** 滚动条宽度。 */
    public static final int WIDTH = 5;

    /** 滑块最小高度，保证极长列表下仍可抓取。 */
    private static final int MIN_THUMB = 20;

    private static int dragId = -1;
    private static int dragStartMouse = 0;
    private static int dragStartOffset = 0;
    private static int dragTotal = 0;
    private static int dragVisible = 0;
    private static int dragTrack = 0;

    private PmScrollbar() {}

    /**
     * 绘制纵向滚动条。
     *
     * @param x 轨道左边界
     * @param y1 轨道上边界
     * @param y2 轨道下边界
     * @return 滑块矩形 {@code {x1,y1,x2,y2}}；不溢出时返回空数组
     */
    public static int[] draw(GuiGraphics g, int x, int y1, int y2, int total, int visible, int offset) {
        int track = y2 - y1;
        if (track <= 0) return new int[0];

        if (total <= visible || total <= 0) {
            // 不溢出：只留一条几乎看不见的轨道，提示这里可以容纳更多
            g.fill(x, y1, x + WIDTH, y2, PmTheme.SCROLL_TRACK);
            return new int[0];
        }

        g.fill(x, y1, x + WIDTH, y2, dragId >= 0 ? PmTheme.SCROLL_TRACK_ACTIVE : PmTheme.SCROLL_TRACK);

        int thumb = Math.max(MIN_THUMB, (int) ((long) track * visible / total));
        int span = Math.max(1, total - visible);
        int maxTop = track - thumb;
        int clamped = Math.max(0, Math.min(offset, span));
        int top = y1 + (int) ((long) maxTop * clamped / span);

        g.fill(x, top, x + WIDTH, top + thumb, PmTheme.PANEL_BORDER_BRIGHT);
        return new int[] {x, top, x + WIDTH, top + thumb};
    }

    /**
     * 处理一次点击：命中滑块则开始拖拽，命中轨道则翻页。
     *
     * @return 新的偏移量；未命中或无需滚动时返回 -1
     */
    public static int clickV(
            double mx, double my, int x1, int x2, int y1, int y2, int total, int visible, int offset, int id) {
        if (total <= visible || total <= 0) return -1;
        if (mx < x1 || mx >= x2 || my < y1 || my >= y2) return -1;

        int track = y2 - y1;
        int thumb = Math.max(MIN_THUMB, (int) ((long) track * visible / total));
        int span = Math.max(1, total - visible);
        int maxTop = track - thumb;
        int clamped = Math.max(0, Math.min(offset, span));
        int top = y1 + (int) ((long) maxTop * clamped / span);

        if (my >= top && my < top + thumb) {
            dragId = id;
            dragStartMouse = (int) my;
            dragStartOffset = clamped;
            dragTotal = total;
            dragVisible = visible;
            dragTrack = maxTop;
            return clamped;
        }
        // 轨道点击：翻一页
        return my < top ? Math.max(0, clamped - visible) : Math.min(span, clamped + visible);
    }

    /** 拖拽中：按鼠标位移换算偏移；不在拖拽或 id 不匹配返回 -1。 */
    public static int dragV(int id, double my) {
        if (dragId != id || dragTrack <= 0) return -1;
        int span = Math.max(1, dragTotal - dragVisible);
        int delta = (int) my - dragStartMouse;
        long moved = (long) delta * span / dragTrack;
        return (int) Math.max(0, Math.min(span, dragStartOffset + moved));
    }

    /** 当前是否有拖拽在进行（用于吞掉其它点击）。 */
    public static boolean isDragging() {
        return dragId >= 0;
    }

    /** 结束拖拽；松开鼠标与界面关闭都必须调用（对称清理）。 */
    public static void endDrag() {
        dragId = -1;
        dragTrack = 0;
    }
}
