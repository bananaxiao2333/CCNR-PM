/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.pm.client.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 弹层几何门禁（docs/03 §5 的硬性约束：最多 8 行、绝不跑出屏幕）。
 *
 * <p>这些约束如果只写在文档里，下一次「顺手把弹层画在鼠标右下角」就会让靠边点击的菜单
 * 有一半跑到屏幕外——玩家既看不到也点不到。算术集中在这里，边界由测试钉死。
 */
class PmPopupLayoutTest {

    @Test
    @DisplayName("宽度按最宽一行算，并夹在 [MIN_W, min(MAX_W, 屏幕宽)] 内")
    void widthIsClamped() {
        assertEquals(PmPopupLayout.MIN_W, PmPopupLayout.width(10, 800), "窄文字也要保证最小宽度");
        assertEquals(120, PmPopupLayout.width(108, 800), "常规宽度 = 文字宽 + 左右内边距");
        assertEquals(PmPopupLayout.MAX_W, PmPopupLayout.width(900, 800), "超长文字不得超过最大宽度");

        // 屏幕比最大宽度还小：必须跟着屏幕收窄，否则弹层会横向溢出
        int narrow = PmPopupLayout.width(900, 150);
        assertTrue(narrow <= 150 - PmPopupLayout.MARGIN * 2, "小屏幕下宽度必须收进屏幕内");
    }

    @Test
    @DisplayName("最多 8 行（超出的行靠滚动看，不能把屏幕铺满）")
    void visibleRowsCapped() {
        assertEquals(0, PmPopupLayout.visibleRows(0));
        assertEquals(5, PmPopupLayout.visibleRows(5));
        assertEquals(PmPopupLayout.MAX_ROWS, PmPopupLayout.visibleRows(PmPopupLayout.MAX_ROWS));
        assertEquals(PmPopupLayout.MAX_ROWS, PmPopupLayout.visibleRows(50));
        assertEquals(PmPopupLayout.MAX_ROWS * PmPopupLayout.ROW_H, PmPopupLayout.height(50));
    }

    @Test
    @DisplayName("屏幕很矮时按屏幕收行数（否则画出来的行既看不到也点不到）")
    void visibleRowsRespectScreenHeight() {
        // 60px 高、行高 12：留出上下 2px 边距后可放 4 行
        int room = (60 - PmPopupLayout.MARGIN * 2) / PmPopupLayout.ROW_H;
        assertEquals(room, PmPopupLayout.visibleRows(50, 60));
        assertTrue(PmPopupLayout.height(50, 60) <= 60 - PmPopupLayout.MARGIN * 2);
        // 高屏不额外放宽：仍然受 MAX_ROWS 限制
        assertEquals(PmPopupLayout.MAX_ROWS, PmPopupLayout.visibleRows(50, 1080));
        // 极矮的屏幕至少留一行，而不是算出 0 行（0 行等于弹层消失）
        assertEquals(1, PmPopupLayout.visibleRows(5, 10));
    }

    @Test
    @DisplayName("常规位置：贴着鼠标向右下展开")
    void anchorsAtMouse() {
        int[] r = PmPopupLayout.anchor(100, 100, 120, 5, 800, 600);
        assertEquals(100, r[0]);
        assertEquals(100, r[1]);
        assertEquals(120, r[2] - r[0]);
        assertEquals(5 * PmPopupLayout.ROW_H, r[3] - r[1]);
    }

    @Test
    @DisplayName("靠右/靠下时向反方向翻，绝不跑出屏幕")
    void flipsAtEdges() {
        int[] right = PmPopupLayout.anchor(790, 100, 120, 5, 800, 600);
        assertTrue(right[2] <= 800 - PmPopupLayout.MARGIN, "右边缘溢出: " + right[2]);

        int[] bottom = PmPopupLayout.anchor(100, 595, 120, 5, 800, 600);
        assertTrue(bottom[3] <= 600 - PmPopupLayout.MARGIN, "下边缘溢出: " + bottom[3]);

        int[] corner = PmPopupLayout.anchor(799, 599, 120, 5, 800, 600);
        assertTrue(corner[0] >= PmPopupLayout.MARGIN && corner[2] <= 800 - PmPopupLayout.MARGIN);
        assertTrue(corner[1] >= PmPopupLayout.MARGIN && corner[3] <= 600 - PmPopupLayout.MARGIN);
    }

    @Test
    @DisplayName("屏幕比弹层还小时贴边显示（宁可挤，也不能溢出到点不到的地方）")
    void tinyScreenStaysInside() {
        int[] r = PmPopupLayout.anchor(50, 50, 300, 8, 120, 60);
        assertTrue(r[0] >= 0 && r[2] <= 120, "小屏幕横向溢出: " + java.util.Arrays.toString(r));
        assertTrue(r[1] >= 0 && r[3] <= 60, "小屏幕纵向溢出: " + java.util.Arrays.toString(r));
    }

    @Test
    @DisplayName("行命中：左闭右开，行与行之间与矩形之外都返回 -1")
    void rowHitTest() {
        int[] r = PmPopupLayout.anchor(10, 10, 120, 3, 800, 600);
        int y1 = r[1];
        int y2 = r[3];
        assertEquals(0, PmPopupLayout.rowAt(y1, y2, y1, 3));
        assertEquals(0, PmPopupLayout.rowAt(y1, y2, y1 + PmPopupLayout.ROW_H - 0.01, 3));
        assertEquals(1, PmPopupLayout.rowAt(y1, y2, y1 + PmPopupLayout.ROW_H, 3));
        assertEquals(2, PmPopupLayout.rowAt(y1, y2, y1 + 2 * PmPopupLayout.ROW_H, 3));
        assertEquals(-1, PmPopupLayout.rowAt(y1, y2, y2, 3), "下边界是开的（矩形之外）");
        assertEquals(-1, PmPopupLayout.rowAt(y1, y2, y1 - 1, 3));
    }

    @Test
    @DisplayName("行命中的范围就是矩形本身（高度已含「能画几行」的信息，不再叠加第二个上限）")
    void rowHitMatchesRectHeight() {
        int y1 = 0;
        int y2 = PmPopupLayout.MAX_ROWS * PmPopupLayout.ROW_H;
        assertEquals(PmPopupLayout.MAX_ROWS - 1, PmPopupLayout.rowAt(y1, y2, y2 - 0.01, 50));
        assertEquals(-1, PmPopupLayout.rowAt(y1, y2, y2, 50));
        // 总行数比矩形还小时（数据在渲染前变短）：越界索引返回 -1 而不是点到不存在的行
        assertEquals(-1, PmPopupLayout.rowAt(y1, y2, y2 - 0.01, 2));
    }
}
