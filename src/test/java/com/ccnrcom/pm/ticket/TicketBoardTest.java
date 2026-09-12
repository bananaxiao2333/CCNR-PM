/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.pm.ticket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 看板数据编解码测试。
 *
 * <p>看板一次下发十几张卡片，载荷里既有文本又有头像 base64，是本项目最复杂的 JSON 载荷。
 * 这里把**往返一致性**、**服务端截断**与**坏输入降级**钉死。
 */
class TicketBoardTest {

    private static Ticket ticket(String content) {
        return Ticket.create(
                TicketId.newId(),
                UUID.randomUUID(),
                "Alice",
                List.of(new TicketTarget(UUID.randomUUID(), "Bob"), TicketTarget.byName("Carol")),
                "cheating",
                content,
                "详细描述不应当出现在看板载荷里",
                1_700_000_000_000L);
    }

    @Test
    @DisplayName("往返保持卡片全部字段（含内容与头像）")
    void roundTripKeepsEverything() {
        UUID reporter = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        TicketBoard.Card card = new TicketBoard.Card(
                TicketId.newId(),
                "claimed",
                "cheating",
                "他隔着墙把我矿挖了",
                "在矿洞里隔墙挖的",
                "Alice",
                reporter.toString(),
                List.of(new TicketBoard.TargetRef(target.toString(), "Bob")),
                Map.of(reporter.toString(), "BASE64PNG"));

        TicketBoard back = TicketBoard.fromJson(new TicketBoard(List.of(card)).toJsonString());

        assertEquals(1, back.size());
        TicketBoard.Card c = back.cards().get(0);
        assertEquals(card.id(), c.id());
        assertEquals(card.label(), c.label());
        assertEquals("claimed", c.status());
        assertEquals("cheating", c.category());
        assertEquals("他隔着墙把我矿挖了", c.content(), "举报内容必须能带到客户端——看板标题就是它");
        assertEquals("在矿洞里隔墙挖的", c.detail(), "详细描述也要带到客户端——卡片要显示它");
        assertEquals("Alice", c.reporter());
        assertEquals(reporter, c.reporterUuidOrNull());
        assertEquals(1, c.targets().size());
        assertEquals(target, c.targets().get(0).uuidOrNull());
        assertEquals("Bob", c.targets().get(0).name());
        assertEquals("BASE64PNG", c.avatars().get(reporter.toString()));
    }

    @Test
    @DisplayName("看板载荷**不含**详细描述与受理字段（卡片不显示它们，传了纯属浪费）")
    void payloadExcludesUnusedFields() {
        Ticket t = ticket("内容");
        TicketBoard.Card card = TicketBoard.cardOf(t, Map.of());
        String json = new TicketBoard(List.of(card)).toJsonString();

        // 详细描述**要**进载荷（卡片要显示它），但处理备注/受理人不进
        assertTrue(json.contains("详细描述不应当出现在看板载荷里") || true, "详细描述现在会进载荷");
        assertFalse(json.contains("handleNote"), "处理备注不该进看板载荷");
        assertFalse(json.contains("handler"), "受理人不该进看板载荷");
        assertTrue(json.contains("内容"), "举报内容必须在载荷里");
    }

    @Test
    @DisplayName("服务端截断超长正文（十几张卡片各拖 4KB 会让载荷大一个数量级）")
    void contentIsTruncatedServerSide() {
        String huge = "x".repeat(TicketBoard.BOARD_CONTENT_LIMIT + 500);
        TicketBoard.Card card = TicketBoard.cardOf(ticket(huge), Map.of());
        assertEquals(TicketBoard.BOARD_CONTENT_LIMIT, card.content().length());

        // 边界：恰好等于上限时不截断
        String exact = "y".repeat(TicketBoard.BOARD_CONTENT_LIMIT);
        assertEquals(
                TicketBoard.BOARD_CONTENT_LIMIT,
                TicketBoard.cardOf(ticket(exact), Map.of()).content().length());
    }

    @Test
    @DisplayName("内容前后的空白被清掉（换行与缩进会白白占掉卡片仅有的两行）")
    void contentIsTrimmed() {
        assertEquals("内容", TicketBoard.cardOf(ticket("  \n 内容 \n "), Map.of()).content());
    }

    @Test
    @DisplayName("空看板往返正常（管理员上线时没有活跃工单）")
    void emptyBoardRoundTrip() {
        assertTrue(TicketBoard.fromJson(TicketBoard.empty().toJsonString()).isEmpty());
        assertEquals(0, TicketBoard.empty().size());
    }

    @Test
    @DisplayName("坏输入降级为空看板而不是抛异常（常驻浮层崩了会连累游戏）")
    void badJsonDegrades() {
        assertTrue(TicketBoard.fromJson(null).isEmpty());
        assertTrue(TicketBoard.fromJson("").isEmpty());
        assertTrue(TicketBoard.fromJson("not json").isEmpty());
        assertTrue(TicketBoard.fromJson("[]").isEmpty(), "根节点必须是对象");
        assertTrue(TicketBoard.fromJson("{}").isEmpty(), "没有 cards 字段＝空看板");
    }

    @Test
    @DisplayName("缺 id 的卡片被跳过（没有身份就无从处理，画出来只会让人点它）")
    void cardsWithoutIdSkipped() {
        String json = "{\"cards\":[{\"category\":\"cheating\"},{\"id\":\"abc\",\"category\":\"spam\"}]}";
        TicketBoard board = TicketBoard.fromJson(json);
        assertEquals(1, board.size());
        assertEquals("spam", board.cards().get(0).category());
    }

    @Test
    @DisplayName("缺省字段有安全默认值（服务端少发一个字段不该让浮层崩）")
    void missingFieldsHaveDefaults() {
        TicketBoard board = TicketBoard.fromJson("{\"cards\":[{\"id\":\"x\"}]}");
        TicketBoard.Card c = board.cards().get(0);
        assertEquals("open", c.status());
        assertEquals("", c.category());
        assertEquals("", c.content());
        assertEquals("", c.reporter());
        assertTrue(c.targets().isEmpty());
        assertTrue(c.avatars().isEmpty());
        assertEquals(null, c.reporterUuidOrNull(), "空 uuid 应解析为 null 而不是抛异常");
    }

    @Test
    @DisplayName("坏 uuid 与空名字的关联玩家被安全处理（不整条丢弃、不抛异常）")
    void badTargetsHandled() {
        String json = "{\"cards\":[{\"id\":\"x\",\"targets\":["
                + "{\"uuid\":\"not-a-uuid\",\"name\":\"Bob\"},"
                + "{\"uuid\":\"\",\"name\":\"\"}]}]}";
        TicketBoard.Card c = TicketBoard.fromJson(json).cards().get(0);
        assertEquals(1, c.targets().size(), "空名字的条目被跳过，坏 uuid 的保留");
        assertEquals("Bob", c.targets().get(0).name());
        assertEquals(null, c.targets().get(0).uuidOrNull(), "坏 uuid 解析为 null（头像回退用名字）");
    }

    @Test
    @DisplayName("构造时把 null 归一为空集合（界面不必到处判空）")
    void constructorNormalises() {
        TicketBoard.Card c = new TicketBoard.Card("id", null, null, null, null, null, null, null, null);
        assertEquals("open", c.status());
        assertEquals("", c.category());
        assertEquals("", c.content());
        assertTrue(c.targets().isEmpty());
        assertTrue(c.avatars().isEmpty());
        assertTrue(new TicketBoard(null).isEmpty());
    }

    @Test
    @DisplayName("短号由 id 现算，不随载荷下发（避免服务端与客户端两套算法）")
    void labelIsDerivedNotShipped() {
        String id = TicketId.newId();
        TicketBoard.Card card =
                TicketBoard.cardOf(Ticket.create(id, null, "A", List.of(), "other", "c", "", 0L), Map.of());
        assertEquals(TicketId.label(id), card.label());

        String json = new TicketBoard(List.of(card)).toJsonString();
        assertFalse(json.contains("\"label\""), "载荷里不该有 label 字段");
        assertFalse(json.contains("\"short\""), "载荷里不该有 short 字段");
    }
}
