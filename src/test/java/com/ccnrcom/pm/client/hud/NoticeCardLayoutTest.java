/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.pm.client.hud;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 看板卡片几何测试。
 *
 * <p>这个类本身就是**两次返工**的产物，所以每条断言都对应一次真实的返工原因：
 * 216×162 太占视野 → 锁尺寸上限；4:3 比例错 → 锁 16:9；
 * 「分布奇怪、不靠边」→ 锁对齐（内容顶边、头像贴底、外边距极小）。
 */
class NoticeCardLayoutTest {

    @Test
    @DisplayName("严格 16:9 横版（用户指定；高度由宽度推出，不手填两个数）")
    void isSixteenByNine() {
        assertEquals(0, NoticeCardLayout.CARD_W % 16, "宽度取 16 的倍数才能整除出整数高度");
        assertEquals(NoticeCardLayout.CARD_W / 16 * 9, NoticeCardLayout.CARD_H);
        assertEquals(160, NoticeCardLayout.CARD_W);
        assertEquals(90, NoticeCardLayout.CARD_H);
    }

    @Test
    @DisplayName("尺寸足够小（两次「太大了」的回归保护）")
    void isSmallEnough() {
        assertTrue(NoticeCardLayout.CARD_W <= 164, "宽度不得再超过 164，实际 " + NoticeCardLayout.CARD_W);
        assertTrue(NoticeCardLayout.CARD_H <= 94, "高度不得再超过 94，实际 " + NoticeCardLayout.CARD_H);
        // 面积约束：单张卡片不该超过屏幕的零头
        assertTrue(NoticeCardLayout.CARD_W * NoticeCardLayout.CARD_H <= 164 * 94, "单卡面积上限被突破");
    }

    @Test
    @DisplayName("靠边：外边距很小（「不靠边」的回归保护）")
    void hugsTheCorner() {
        assertTrue(NoticeCardLayout.MARGIN <= 4, "距屏幕左上角的间距应很小，实际 " + NoticeCardLayout.MARGIN);
        assertEquals(0, NoticeCardLayout.cardY(0) - NoticeCardLayout.MARGIN, "第一张卡片应贴在边距处");
        // 堆叠间距同样要小，否则多张卡片很快占满半屏
        assertTrue(NoticeCardLayout.STACK_GAP <= 4, "卡片间距应很小");
    }

    @Test
    @DisplayName("对齐：标题顶边、头像贴底，两端各自靠边（不再做垂直居中）")
    void contentIsFlushToBothEdges() {
        assertEquals(NoticeCardLayout.PAD, NoticeCardLayout.titleY(), "标题行必须顶着上内边距");
        assertEquals(
                NoticeCardLayout.CARD_H - NoticeCardLayout.PAD,
                NoticeCardLayout.avatarRowBottom(),
                "头像行底边必须顶着下内边距（贴底）");
        assertTrue(NoticeCardLayout.titleY() < NoticeCardLayout.detailY());
        assertTrue(NoticeCardLayout.detailY() < NoticeCardLayout.nameY());
        assertTrue(NoticeCardLayout.nameY() < NoticeCardLayout.avatarRowY(), "小名字行必须在头像正上方");
    }

    @Test
    @DisplayName("详细描述放得下，且不压到小名字行与头像行")
    void detailLinesFit() {
        int bottom = NoticeCardLayout.detailY() + NoticeCardLayout.DETAIL_LINES * NoticeCardLayout.LINE;
        assertTrue(bottom <= NoticeCardLayout.nameY(), "描述底端=" + bottom + " 不得越过小名字行顶端=" + NoticeCardLayout.nameY());
        assertTrue(NoticeCardLayout.DETAIL_LINES >= 1, "至少要能显示一行描述");
        assertTrue(NoticeCardLayout.detailHeight() > 0, "描述可用高度必须为正");
    }

    @Test
    @DisplayName("标题行三段（标题 / 类别 / 状态）恰好铺满内容宽度，绝不互相重叠")
    void titleRowSegmentsFillTheRow() {
        // 中文状态名 3 字 ≈ 27px、类别「作弊 / 外挂」≈ 50px、英文状态 "Rejected" ≈ 44px
        for (int statusPx : new int[] {20, 27, 44, 200}) {
            for (int catPx : new int[] {0, 20, 50, 200}) {
                int statusW = NoticeCardLayout.statusWidth(statusPx);
                int catW = NoticeCardLayout.categoryWidthFor(statusW, catPx);
                int titleW = NoticeCardLayout.titleWidthFor(statusW, catW);
                assertEquals(
                        NoticeCardLayout.CARD_W - NoticeCardLayout.PAD,
                        NoticeCardLayout.PAD
                                + titleW
                                + NoticeCardLayout.TAG_GAP
                                + catW
                                + NoticeCardLayout.STATUS_GAP
                                + statusW,
                        "status=" + statusPx + " category=" + catPx + " 时三段相加应恰好等于内容宽度");
            }
        }
    }

    @Test
    @DisplayName("状态标签必须完整可读（用户要求「状态要能分辨」），标题有下限，让位的是类别")
    void statusStaysReadableAndCategoryYields() {
        // 状态宽度按测量值走，只在超过上限时才裁
        assertEquals(27, NoticeCardLayout.statusWidth(27));
        assertEquals(NoticeCardLayout.STATUS_MAX_W, NoticeCardLayout.statusWidth(500));

        // 标题永不低于下限；类别是三者中唯一会被裁的
        int statusW = NoticeCardLayout.statusWidth(NoticeCardLayout.STATUS_MAX_W);
        int catW = NoticeCardLayout.categoryWidthFor(statusW, 500);
        assertTrue(NoticeCardLayout.titleWidthFor(statusW, catW) >= NoticeCardLayout.MIN_TITLE_W);
        assertTrue(catW < 500, "类别必须让位（否则标题会被挤没）");
        assertTrue(catW >= 0, "类别宽度不能为负");
        assertTrue(NoticeCardLayout.MIN_TITLE_W >= 40, "标题下限太小就等于把主内容裁没了");
    }

    @Test
    @DisplayName("状态标签与类别右对齐在卡片内边距上（不会画到卡片外）")
    void rightTagsHugThePadding() {
        int statusW = NoticeCardLayout.statusWidth(27);
        assertEquals(NoticeCardLayout.CARD_W - NoticeCardLayout.PAD, NoticeCardLayout.statusX(statusW) + statusW);
        assertEquals(
                NoticeCardLayout.statusX(statusW),
                NoticeCardLayout.categoryRight(statusW) + NoticeCardLayout.STATUS_GAP,
                "类别右边缘与状态左边缘之间只隔 STATUS_GAP");
    }

    @Test
    @DisplayName("交互模式：卡片在只读版式下方多出一排按钮的高度")
    void interactiveCardIsTallerByButtonRow() {
        assertEquals(NoticeCardLayout.CARD_H + NoticeCardLayout.BUTTON_H + 2, NoticeCardLayout.cardHeightInteractive());
        assertTrue(NoticeCardLayout.cardHeightInteractive() > NoticeCardLayout.CARD_H);
        // 交互态堆叠不能重叠
        assertTrue(NoticeCardLayout.cardYInteractive(0) + NoticeCardLayout.cardHeightInteractive()
                <= NoticeCardLayout.cardYInteractive(1));
    }

    @Test
    @DisplayName("关联玩家一侧至少放得下 3 个头像（否则「一排」没有意义）")
    void relatedSideHasRoom() {
        assertTrue(NoticeCardLayout.relatedCapacity() >= 3, "实际容量 " + NoticeCardLayout.relatedCapacity());
        assertTrue(NoticeCardLayout.relatedWidth() > 0);
    }

    @Test
    @DisplayName("竖线两侧的间距已从可用宽度里扣掉（否则最后一个头像会压出卡片）")
    void dividerGapIsAccountedFor() {
        assertEquals(
                NoticeCardLayout.innerWidth()
                        - NoticeCardLayout.HEAD
                        - NoticeCardLayout.DIVIDER_GAP
                        - 1
                        - NoticeCardLayout.DIVIDER_GAP,
                NoticeCardLayout.relatedWidth());
    }

    @Test
    @DisplayName("头像不收额外边距、紧挨着排是刻意的（「一排无间隔」）")
    void avatarsAreAdjacent() {
        assertEquals(
                NoticeCardLayout.HEAD
                        + NoticeCardLayout.DIVIDER_GAP
                        + 1
                        + NoticeCardLayout.DIVIDER_GAP
                        + NoticeCardLayout.relatedWidth(),
                NoticeCardLayout.innerWidth(),
                "除竖线占位外不应再有额外间距");
    }

    @Test
    @DisplayName("溢出时留一个位置给「+N」计数，并提示剩余张数")
    void overflowReservesSlotForCounter() {
        int cap = NoticeCardLayout.relatedCapacity();

        assertFalse(NoticeCardLayout.overflows(cap));
        assertEquals(cap, NoticeCardLayout.relatedToDraw(cap), "恰好放得下就全画");

        assertTrue(NoticeCardLayout.overflows(cap + 1));
        assertEquals(cap - 1, NoticeCardLayout.relatedToDraw(cap + 1), "溢出时少画一个，腾位给 +N");

        // 工单最多 8 个关联玩家：必须既画得出头像、又能表达「还有几个」
        assertTrue(NoticeCardLayout.overflows(8));
        int drawn = NoticeCardLayout.relatedToDraw(8);
        assertEquals(cap - 1, drawn);
        assertTrue(8 - drawn > 0, "隐藏数量应为正，供 +N 显示");
    }

    @Test
    @DisplayName("零个关联玩家是合法情况（该字段选填）")
    void emptyRelatedIsFine() {
        assertFalse(NoticeCardLayout.overflows(0));
        assertEquals(0, NoticeCardLayout.relatedToDraw(0));
    }

    @Test
    @DisplayName("卡片逐张向下排列，互不重叠")
    void cardsStackWithoutOverlap() {
        int y0 = NoticeCardLayout.cardY(0);
        int y1 = NoticeCardLayout.cardY(1);
        assertTrue(y1 > y0, "第二张应在第一张下方");
        assertTrue(y0 + NoticeCardLayout.CARD_H <= y1, "第一张卡片的底边不得越过第二张的顶边");
    }

    @Test
    @DisplayName("同屏卡片数有上限（过多时折叠成「+N」，不铺满屏幕）")
    void maxVisibleIsBounded() {
        assertTrue(NoticeCardLayout.MAX_VISIBLE >= 2, "至少能同时看到两张，否则看板没意义");
        assertTrue(NoticeCardLayout.MAX_VISIBLE <= 6, "上限过大就会铺满屏幕，实际 " + NoticeCardLayout.MAX_VISIBLE);
    }
}
