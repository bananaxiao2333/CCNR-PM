/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.pm.client.hud;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ccnrcom.pm.ticket.TicketBoard;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 看板头像命中几何门禁。
 *
 * <h2>为什么值得单测</h2>
 * 「点左边的头像弹出右边玩家的菜单」是典型的**只在特定名字长度/关联人数下复现**的缺陷，
 * 而且在禁止起服务的项目里根本没法肉眼验证。位置算法既然是纯算术，就必须由测试钉死：
 * 槽位坐标必须与 {@link NoticeCardLayout} 的常量一致、画不出来的头像不许留下可点区域。
 */
class BoardAvatarLayoutTest {

    private static final String REPORTER_UUID = "11111111-1111-1111-1111-111111111111";

    private static TicketBoard.Card card(String reporter, String reporterUuid, String... targets) {
        List<TicketBoard.TargetRef> refs = new ArrayList<>();
        for (int i = 0; i < targets.length; i++) {
            // 偶数下标给 UUID、奇数下标只给名字（模拟手输的离线关联玩家）
            refs.add(new TicketBoard.TargetRef(
                    i % 2 == 0 ? "2222222" + i + "-2222-2222-2222-222222222222" : "", targets[i]));
        }
        return new TicketBoard.Card(
                "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee",
                "open",
                "cheating",
                "内容",
                "描述",
                reporter,
                reporterUuid,
                refs,
                Map.of());
    }

    @Test
    @DisplayName("提交者固定在第一位，其后是关联玩家")
    void submitterComesFirst() {
        List<BoardAvatarLayout.Slot> slots =
                BoardAvatarLayout.slots(card("Alice", REPORTER_UUID, "Bob", "Carol"), 0, 0);
        assertEquals(3, slots.size());
        assertEquals("Alice", slots.get(0).name());
        assertTrue(slots.get(0).submitter(), "第一位必须是提交者（亮框的那个）");
        assertEquals("Bob", slots.get(1).name());
        assertEquals("Carol", slots.get(2).name());
        assertFalse(slots.get(1).submitter());
    }

    @Test
    @DisplayName("槽位坐标与卡片几何常量一致（两处算法漂移会让点击错位）")
    void positionsMatchCardGeometry() {
        int cardX = 13;
        int cardY = 40;
        List<BoardAvatarLayout.Slot> slots = BoardAvatarLayout.slots(card("Alice", REPORTER_UUID, "Bob"), cardX, cardY);

        int expectedY = cardY + NoticeCardLayout.avatarRowY();
        assertEquals(cardX + NoticeCardLayout.PAD, slots.get(0).x(), "提交者头像紧贴卡片内边距");
        assertEquals(expectedY, slots.get(0).y(), "头像行必须贴卡片底部（与只读浮层同一行）");
        assertEquals(cardX + BoardAvatarLayout.targetHeadX(0), slots.get(1).x());

        // 关联玩家必须排在竖线右侧（竖线占 1px，两侧各留 DIVIDER_GAP）
        int dividerX = cardX + NoticeCardLayout.PAD + NoticeCardLayout.HEAD + NoticeCardLayout.DIVIDER_GAP;
        assertTrue(slots.get(1).x() > dividerX + 1, "关联玩家头像必须落在分隔竖线右侧");

        // 每个槽位边长都是常量 HEAD，且不越过卡片内边距
        for (BoardAvatarLayout.Slot s : slots) {
            assertEquals(NoticeCardLayout.HEAD, s.size());
            assertTrue(s.x2() <= cardX + NoticeCardLayout.CARD_W - NoticeCardLayout.PAD, "头像越出卡片右内边距");
            assertTrue(s.y2() <= cardY + NoticeCardLayout.CARD_H, "头像越出卡片底边");
        }
    }

    @Test
    @DisplayName("溢出时只给「画得出来」的头像留可点区域（看不见却可点等于随机点到别人）")
    void overflowSlotsMatchDrawnHeads() {
        String[] many = new String[12];
        for (int i = 0; i < many.length; i++) many[i] = "P" + i;
        List<BoardAvatarLayout.Slot> slots = BoardAvatarLayout.slots(card("Alice", REPORTER_UUID, many), 0, 0);

        int drawn = NoticeCardLayout.relatedToDraw(many.length);
        assertEquals(1 + drawn, slots.size(), "槽位数必须等于真正画出来的头像数（提交者 + relatedToDraw）");
        assertTrue(slots.size() < 1 + many.length, "溢出的关联玩家不该留下不可见的可点区域");
        // 最后一个槽位右边缘仍在卡片内
        BoardAvatarLayout.Slot last = slots.get(slots.size() - 1);
        assertTrue(last.x2() <= NoticeCardLayout.CARD_W - NoticeCardLayout.PAD);
    }

    @Test
    @DisplayName("没有名字的提交者不产生槽位（否则会出现一处看不见却能点的热区）")
    void blankReporterProducesNoSlot() {
        List<BoardAvatarLayout.Slot> slots = BoardAvatarLayout.slots(card("", "", "Bob"), 0, 0);
        assertEquals(1, slots.size());
        assertEquals("Bob", slots.get(0).name());
    }

    @Test
    @DisplayName("命中测试：边界左闭右开，缝隙里不命中")
    void hitTestUsesHalfOpenBounds() {
        List<BoardAvatarLayout.Slot> slots = BoardAvatarLayout.slots(card("Alice", REPORTER_UUID, "Bob"), 0, 0);
        BoardAvatarLayout.Slot reporter = slots.get(0);
        BoardAvatarLayout.Slot target = slots.get(1);

        assertNotNull(BoardAvatarLayout.hit(slots, reporter.x(), reporter.y()));
        assertNotNull(BoardAvatarLayout.hit(slots, reporter.x2() - 0.01, reporter.y2() - 0.01));
        assertNull(BoardAvatarLayout.hit(slots, reporter.x2(), reporter.y()), "右边界是开的，不该命中");

        // 提交者与关联玩家之间的竖线缝里不命中任何头像
        double gapX = reporter.x2() + NoticeCardLayout.DIVIDER_GAP;
        assertNull(BoardAvatarLayout.hit(slots, gapX, reporter.y() + 1));

        assertNotNull(BoardAvatarLayout.hit(slots, target.x() + 1, target.y() + 1));
        assertNull(BoardAvatarLayout.hit(slots, 0, 0), "卡片外的坐标不该命中");
        assertNull(BoardAvatarLayout.hit(List.of(), 0, 0));
    }

    @Test
    @DisplayName("离线手输的关联玩家没有 UUID（交互时只能靠名字定位，不能假装有 UUID）")
    void offlineTargetHasNoUuid() {
        List<BoardAvatarLayout.Slot> slots =
                BoardAvatarLayout.slots(card("Alice", REPORTER_UUID, "Bob", "Offline"), 0, 0);
        assertTrue(slots.get(1).hasUuid(), "带 UUID 的关联玩家应可精确定位");
        assertFalse(slots.get(2).hasUuid(), "手输名字没有 UUID");
        assertEquals("", slots.get(2).uuid());
    }

    @Test
    @DisplayName("null 卡片安全返回空槽位（浮层每帧都画，不能因为一张坏卡片崩掉）")
    void nullCardIsSafe() {
        assertTrue(BoardAvatarLayout.slots(null, 0, 0).isEmpty());
    }
}
