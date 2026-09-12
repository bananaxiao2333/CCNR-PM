/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.pm.ticket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 管理面板排序测试（纯逻辑）。
 *
 * <p>排序规则是「管理员扫一眼就知道先处理哪个」的核心，而比较器最容易写错的地方是
 * **不满足全序**——那会让列表每次刷新都跳位（同一个工单一会儿上一会儿下，无法稳定点击）。
 * 因此这里除了验证顺序，还专门验证「比较器是全序」。
 */
class TicketSortTest {

    private static final String ME = "Admin";

    /** 按 Entry 的**真实字段顺序**构造：id, status, reporter, targets, category, content, detail, createdAt, handler, note。 */
    private static TicketPage.Entry entry(String id, String status, String category, long createdAt, String handler) {
        return new TicketPage.Entry(
                id, status, "Alice", "", category, "举报内容", "", createdAt, handler == null ? "" : handler, "");
    }

    @Test
    @DisplayName("我认领的排最前，其次待处理，再次别人认领的，收尾态最后")
    void priorityOrder() {
        List<TicketPage.Entry> list = new ArrayList<>(List.of(
                entry("closed", "closed", "cheating", 100, "Other"),
                entry("otherClaim", "claimed", "cheating", 200, "Other"),
                entry("open", "open", "cheating", 300, null),
                entry("mine", "claimed", "cheating", 50, ME)));
        list.sort(TicketSort.comparator(ME));

        assertEquals(
                List.of("mine", "open", "otherClaim", "closed"),
                list.stream().map(TicketPage.Entry::id).toList());
    }

    @Test
    @DisplayName("大小写不同的受理人名字也算「我的」（服务端名字大小写可能不一致）")
    void ownClaimIsCaseInsensitive() {
        TicketPage.Entry e = entry("a", "claimed", "x", 1, "aDmIn");
        assertEquals(0, TicketSort.priority(e, ME));
    }

    @Test
    @DisplayName("查看者名字为空时不把任何工单当「我的」")
    void blankViewerNoOwnClaim() {
        TicketPage.Entry e = entry("a", "claimed", "x", 1, ""); // 受理人为空
        assertEquals(2, TicketSort.priority(e, null), "认领态但受理人空 → 归入「别人认领的」");
        assertEquals(2, TicketSort.priority(e, ""));
    }

    @Test
    @DisplayName("同优先级内先按类别（用户要求按类别排序）")
    void sortedByCategoryWithinGroup() {
        List<TicketPage.Entry> list = new ArrayList<>(List.of(
                entry("spam", "open", "spam", 100, null),
                entry("cheat", "open", "cheating", 200, null),
                entry("grief", "open", "griefing", 300, null)));
        list.sort(TicketSort.comparator(ME));

        assertEquals(
                List.of("cheat", "grief", "spam"),
                list.stream().map(TicketPage.Entry::id).toList());
    }

    @Test
    @DisplayName("同类别内按时间倒序（新的在前）")
    void newestFirstWithinCategory() {
        List<TicketPage.Entry> list = new ArrayList<>(List.of(
                entry("old", "open", "spam", 100, null),
                entry("new", "open", "spam", 300, null),
                entry("mid", "open", "spam", 200, null)));
        list.sort(TicketSort.comparator(ME));

        assertEquals(
                List.of("new", "mid", "old"),
                list.stream().map(TicketPage.Entry::id).toList());
    }

    @Test
    @DisplayName("类别为空的排在同组最后（空类别不该插在字母序最前）")
    void emptyCategoryLast() {
        List<TicketPage.Entry> list = new ArrayList<>(
                List.of(entry("none", "open", "", 100, null), entry("spam", "open", "spam", 100, null)));
        list.sort(TicketSort.comparator(ME));

        assertEquals("spam", list.get(0).id());
        assertEquals("none", list.get(1).id());
    }

    @Test
    @DisplayName("比较器是全序：同一对工单反复比较结果一致，不会让列表跳位")
    void comparatorIsTotalOrder() {
        var cmp = TicketSort.comparator(ME);
        // 时间完全相同 → 必须靠 id 兜底，否则 sort 的结果取决于输入顺序（每次刷新跳位）
        TicketPage.Entry a = entry("aaa", "open", "spam", 100, null);
        TicketPage.Entry b = entry("bbb", "open", "spam", 100, null);

        assertTrue(cmp.compare(a, b) < 0, "id 兜底：aaa 应在 bbb 前");
        assertEquals(-cmp.compare(b, a), cmp.compare(a, b), "反对称性");
        assertEquals(0, cmp.compare(a, a), "自反性");

        // 传递性抽查
        TicketPage.Entry c = entry("ccc", "open", "spam", 100, null);
        assertTrue(cmp.compare(a, c) < 0);
        assertTrue(cmp.compare(b, c) < 0);
    }

    @Test
    @DisplayName("null 不炸（界面数据来自网络，容忍坏输入）")
    void toleratesNulls() {
        assertEquals(9, TicketSort.priority(null, ME));
        // 类别为 null 的条目不应让比较抛异常
        TicketPage.Entry a = new TicketPage.Entry("x", "open", "A", "", null, "c", "", 1L, null, "");
        TicketPage.Entry b = new TicketPage.Entry("y", "open", "A", "", null, "c", "", 1L, null, "");
        TicketSort.comparator(ME).compare(a, b);
    }

    @Test
    @DisplayName("排序不改变元素集合（只重排）")
    void sortIsPermutation() {
        List<TicketPage.Entry> list = new ArrayList<>(List.of(
                entry("a", "open", "spam", 1, null),
                entry("b", "claimed", "spam", 2, ME),
                entry("c", "closed", "spam", 3, "X"),
                entry("d", "rejected", "spam", 4, "X")));
        List<String> before = list.stream().map(TicketPage.Entry::id).sorted().toList();
        list.sort(TicketSort.comparator(ME));
        assertEquals(before, list.stream().map(TicketPage.Entry::id).sorted().toList());
        assertEquals("b", list.get(0).id(), "我认领的应在最前");
    }
}
