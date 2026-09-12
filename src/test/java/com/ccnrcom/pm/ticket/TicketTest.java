/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.pm.ticket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 工单实体、状态与关联玩家列表的纯逻辑测试。 */
class TicketTest {

    private static final String ID = "3f2a91c4-5b7e-4d18-9a6f-0c1d2e3f4a5b";

    private static Ticket sample(List<TicketTarget> targets) {
        return Ticket.create(ID, UUID.randomUUID(), "Alice", targets, "cheating", "原文", "细节", 123L);
    }

    @Test
    @DisplayName("持久化字面量为小写，且能容错解析回枚举")
    void statusParsingIsTolerant() {
        assertEquals("open", TicketStatus.OPEN.id());
        assertEquals(TicketStatus.OPEN, TicketStatus.parse("open"));
        assertEquals(TicketStatus.CLAIMED, TicketStatus.parse("CLAIMED"));
        assertEquals(TicketStatus.CLOSED, TicketStatus.parse(" closed "));
        assertEquals(TicketStatus.OPEN, TicketStatus.parse("garbage"), "未知值回退待处理，不让服务停摆");
        assertEquals(TicketStatus.OPEN, TicketStatus.parse(null));
        assertEquals(TicketStatus.OPEN, TicketStatus.parse(""));
    }

    @Test
    @DisplayName("未办结状态才是「占用受理队列」的状态")
    void unresolvedStates() {
        assertTrue(TicketStatus.OPEN.isUnresolved());
        assertTrue(TicketStatus.CLAIMED.isUnresolved());
        assertFalse(TicketStatus.CLOSED.isUnresolved());
        assertFalse(TicketStatus.REJECTED.isUnresolved());
    }

    @Test
    @DisplayName("新建工单默认待处理且无受理人")
    void createDefaults() {
        Ticket t = sample(List.of(new TicketTarget(null, "Bob")));
        assertEquals(TicketStatus.OPEN, t.status());
        assertEquals(0L, t.handledAt());
        assertNull(t.handlerName());
    }

    @Test
    @DisplayName("工单身份是完整 UUID；显示用的是它的前 8 位短号")
    void identityIsUuid() {
        Ticket t = sample(List.of());
        assertEquals(ID, t.id(), "身份就是 UUID 本身");
        assertEquals("3f2a91c4", t.shortId());
        assertEquals("#3f2a91c4", t.label(), "展示形式是短号，但身份不是它");
        assertTrue(TicketId.isFullId(t.id()));
    }

    @Test
    @DisplayName("状态迁移只动状态与受理字段，举报原文与描述永不改写（证据完整性）")
    void withStatusPreservesContent() {
        Ticket t = sample(List.of(new TicketTarget(UUID.randomUUID(), "Bob")));
        Ticket closed = t.withStatus(TicketStatus.CLOSED, "Admin", 900L, "已警告");

        assertEquals(ID, closed.id(), "状态迁移不得改变身份");
        assertEquals("原文", closed.content(), "处理工单不得改写举报原文");
        assertEquals("细节", closed.detail());
        assertEquals(123L, closed.createdAt());
        assertEquals(900L, closed.handledAt());
        assertEquals("Admin", closed.handlerName());
        assertEquals("已警告", closed.handleNote());
        assertEquals(TicketStatus.CLOSED, closed.status());
        assertEquals(t.targets(), closed.targets(), "关联玩家也不得被处理动作改写");
    }

    @Test
    @DisplayName("关联玩家可以为空列表（该字段选填）")
    void emptyTargetsAllowed() {
        Ticket t = Ticket.create(ID, null, "A", null, "other", "c", "", 0L);
        assertTrue(t.targets().isEmpty(), "传 null 也应当归一为空列表");
        assertEquals("", t.targetLabel());
    }

    @Test
    @DisplayName("关联玩家显示为逗号分隔的名字串")
    void targetLabelJoinsNames() {
        Ticket t = sample(List.of(new TicketTarget(null, "Bob"), new TicketTarget(null, "Carol")));
        assertEquals("Bob, Carol", t.targetLabel());
    }

    @Test
    @DisplayName("id 为空时构造失败（身份缺失应当在开发期就暴露）")
    void blankIdRejected() {
        assertThrows(
                IllegalArgumentException.class, () -> Ticket.create("", null, "A", List.of(), "other", "c", "", 0L));
    }

    // ------------------------------------------------------------------
    // TicketId：短号、前缀匹配、查询合法性
    // ------------------------------------------------------------------

    @Test
    @DisplayName("短号取规范化后的前 8 位，忽略大小写与连字符")
    void shortIdNormalises() {
        assertEquals("3f2a91c4", TicketId.shortId(ID));
        assertEquals("3f2a91c4", TicketId.shortId(ID.replace("-", "")));
        assertEquals("3f2a91c4", TicketId.shortId(ID.toUpperCase(java.util.Locale.ROOT)));
        assertEquals("#3f2a91c4", TicketId.label(ID));
    }

    @Test
    @DisplayName("短号不是身份：它只是前缀，匹配始终落在完整标识上")
    void shortIdIsOnlyDisplay() {
        // 两张工单共享前 8 位短号是完全可能的（概率极低但存在），
        // 因此匹配函数必须按完整标识判等，短号只用于「用户输入」这一侧
        String a = "3f2a91c4-0000-0000-0000-000000000001";
        String b = "3f2a91c4-0000-0000-0000-000000000002";
        assertEquals(TicketId.shortId(a), TicketId.shortId(b), "构造出的短号相同");
        assertTrue(TicketId.matches(a, "3f2a91c4"));
        assertTrue(TicketId.matches(b, "3f2a91c4"), "两者都会被这个短号命中——所以查询必须能报歧义");
        assertFalse(TicketId.matches(a, b), "完整标识不同就是不同工单");
    }

    @Test
    @DisplayName("前缀匹配忽略大小写与连字符（玩家会随手粘贴）")
    void matchesIsForgiving() {
        assertTrue(TicketId.matches(ID, "3f2a"));
        assertTrue(TicketId.matches(ID, "3F2A91C4"));
        assertTrue(TicketId.matches(ID, "3f-2a"));
        assertTrue(TicketId.matches(ID, ID), "完整 UUID 也必须能匹配");
        assertFalse(TicketId.matches(ID, "zzzz"));
        assertFalse(TicketId.matches(ID, ""), "空查询不得匹配任何工单");
        assertFalse(TicketId.matches(null, "3f2a"));
    }

    @Test
    @DisplayName("查询串太短或含非十六进制字符时判为非法")
    void queryValidation() {
        assertFalse(TicketId.isValidQuery(null));
        assertFalse(TicketId.isValidQuery(""));
        assertFalse(TicketId.isValidQuery("abc"), "少于 4 位会命中太多工单");
        assertTrue(TicketId.isValidQuery("abcd"));
        assertTrue(TicketId.isValidQuery(ID), "完整 UUID 含连字符也要接受");
        assertFalse(TicketId.isValidQuery("abcd-efgh"), "g/h 不是十六进制");
        assertFalse(TicketId.isValidQuery("不是hex"));
    }

    @Test
    @DisplayName("新标识是合法 UUID 且不重复")
    void newIdIsUniqueUuid() {
        String a = TicketId.newId();
        String b = TicketId.newId();
        assertTrue(TicketId.isFullId(a));
        assertTrue(TicketId.isFullId(b));
        assertFalse(a.equals(b));
    }

    @Test
    @DisplayName("坏标识不应让界面崩掉：标签退化为 #?")
    void labelToleratesBadId() {
        assertEquals("#?", TicketId.label(null));
        assertEquals("#?", TicketId.label(""));
        assertEquals("3f2a", TicketId.shortId("3f2a"), "不足 8 位时原样返回");
    }

    // ------------------------------------------------------------------
    // TicketTarget：JSON 往返与列表工具
    // ------------------------------------------------------------------

    @Test
    @DisplayName("关联玩家 JSON 往返保持 UUID 与名字")
    void targetJsonRoundTrip() {
        UUID u = UUID.randomUUID();
        List<TicketTarget> in = List.of(new TicketTarget(u, "Bob"), TicketTarget.byName("Carol"));
        List<TicketTarget> out = TicketTarget.listFromJson(TicketTarget.listToJson(in));

        assertEquals(2, out.size());
        assertEquals(u, out.get(0).uuid());
        assertEquals("Bob", out.get(0).name());
        assertNull(out.get(1).uuid(), "离线玩家解析不到 UUID，应当保持 null");
        assertEquals("Carol", out.get(1).name());
    }

    @Test
    @DisplayName("关联玩家 JSON 损坏时返回空列表，不抛异常")
    void targetJsonTolerant() {
        assertEquals(List.of(), TicketTarget.listFromJson(null));
        assertEquals(List.of(), TicketTarget.listFromJson(""));
        assertEquals(List.of(), TicketTarget.listFromJson("not json"));
        assertEquals(List.of(), TicketTarget.listFromJson("{\"not\":\"array\"}"));
        assertEquals(
                1,
                TicketTarget.listFromJson("[{\"name\":\"Bob\"},{\"uuid\":null}]")
                        .size());
    }

    @Test
    @DisplayName("关联玩家名不可为空，构造时 trim")
    void targetNameRules() {
        assertThrows(IllegalArgumentException.class, () -> new TicketTarget(null, "  "));
        assertThrows(IllegalArgumentException.class, () -> new TicketTarget(null, null));
        assertEquals("Bob", new TicketTarget(null, "  Bob  ").name());
        assertNotNull(TicketTarget.byName("Bob"));
    }
}
