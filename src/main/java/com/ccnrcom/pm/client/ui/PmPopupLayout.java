/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.pm.client.ui;

/**
 * 弹层（右键快捷菜单 / 角色选择器）的**几何**（纯逻辑，无 MC 依赖，可直接单测）。
 *
 * <p>docs/03 §5 对弹层有几条硬性约束：最多 8 行、溢出时可滚轮与拖拽、**绝不跑出面板/屏幕**。
 * 这几条都是像素算术，写在渲染代码里就只能靠肉眼验证——而项目禁止起服务，
 * 所以算术集中到这个纯类，由 {@code PmPopupLayoutTest} 钉死边界。
 *
 * <h2>锚点约定</h2>
 * 弹层以鼠标位置为锚点，默认**向右下展开**；碰到屏幕边缘时向左上翻，仍然放不下就贴边
 * （而不是溢出到屏幕外——溢出部分玩家既看不到也点不到，等于按钮凭空消失）。
 */
public final class PmPopupLayout {

    /** 单行高度。 */
    public static final int ROW_H = 12;

    /** 同屏最多显示几行，其余靠滚动（与 docs/03 §5 的「弹层最多 8 行」一致）。 */
    public static final int MAX_ROWS = 8;

    /** 弹层最小宽度（太窄会把中文行裁成半截）。 */
    public static final int MIN_W = 88;

    /** 弹层最大宽度。 */
    public static final int MAX_W = 220;

    /** 左右内边距（文字两侧留白）。 */
    public static final int PAD_X = 6;

    /** 与屏幕边缘保留的间距。 */
    public static final int MARGIN = 2;

    /** 弹层宽度：按最宽一行文字算，再夹到合理区间。 */
    public static int width(int widestText, int screenW) {
        int wanted = Math.max(MIN_W, widestText + PAD_X * 2);
        int capped = Math.min(MAX_W, Math.max(MIN_W, screenW - MARGIN * 2));
        return Math.min(wanted, capped);
    }

    /** 实际画几行（不管总数多少，最多 {@link #MAX_ROWS}）。 */
    public static int visibleRows(int total) {
        return Math.max(0, Math.min(total, MAX_ROWS));
    }

    /**
     * 实际画几行，**同时受屏幕高度约束**。
     *
     * <p>屏幕比 {@code MAX_ROWS} 行还矮时必须以屏幕为准：宁可让玩家滚动，
     * 也不能画出一个下缘在屏幕外的弹层（那几行既看不到也点不到）。
     */
    public static int visibleRows(int total, int screenH) {
        int room = Math.max(1, (screenH - MARGIN * 2) / ROW_H);
        return Math.max(0, Math.min(visibleRows(total), room));
    }

    /** 总高度（不含描边余量；描边画在矩形上）。 */
    public static int height(int rows) {
        return visibleRows(rows) * ROW_H;
    }

    /** 总高度（受屏幕高度约束）。 */
    public static int height(int rows, int screenH) {
        return visibleRows(rows, screenH) * ROW_H;
    }

    /**
     * 计算弹层矩形 {@code {x1, y1, x2, y2}}（屏幕坐标，已夹进屏幕内）。
     *
     * @param anchorX 鼠标 X
     * @param anchorY 鼠标 Y
     * @param w       期望宽度（由 {@link #width} 得到）
     * @param rows    行数（超过 {@link #MAX_ROWS} 或超屏时按可视行数算高度）
     */
    public static int[] anchor(int anchorX, int anchorY, int w, int rows, int screenW, int screenH) {
        int width = Math.max(1, Math.min(w, Math.max(1, screenW - MARGIN * 2)));
        int height = Math.max(1, height(rows, screenH));
        int maxX = Math.max(MARGIN, screenW - MARGIN - width);
        int maxY = Math.max(MARGIN, screenH - MARGIN - height);

        // 右上角放不下就向左翻，向下放不下就向上翻；仍然放不下（弹层比屏幕还大）则贴边
        int x1 = anchorX + width > screenW - MARGIN ? anchorX - width : anchorX;
        int y1 = anchorY + height > screenH - MARGIN ? anchorY - height : anchorY;
        x1 = Math.max(MARGIN, Math.min(x1, maxX));
        y1 = Math.max(MARGIN, Math.min(y1, maxY));
        return new int[] {x1, y1, x1 + width, y1 + height};
    }

    /**
     * 命中第几行（相对可视区第一条）。
     *
     * <p>只按**矩形高度**判定：矩形已经编码了「看得见几行」（{@link #anchor} 会按屏幕夹高度），
     * 再叠加一次行数上限只会制造第二个真相来源。返回 {@code -1} 表示点在矩形之外，
     * 调用方据此判断「点了空白处」，而不是把它当成第 0 行。
     */
    public static int rowAt(int rectY1, int rectY2, double my, int totalRows) {
        if (my < rectY1 || my >= rectY2) return -1;
        int idx = (int) ((my - rectY1) / ROW_H);
        return idx < totalRows ? idx : -1;
    }

    private PmPopupLayout() {}
}
