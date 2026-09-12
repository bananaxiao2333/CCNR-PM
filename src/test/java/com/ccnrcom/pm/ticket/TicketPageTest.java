/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.pm.ticket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 管理面板数据编解码测试。
 *
 * <p>面板用 JSON 下发整页数据（结构会变的管理数据，逐字段编解码会让每加一个字段都要动协议两端），
 * 代价是失去编译期字段校验——因此这里把**往返一致性**与**坏输入降级**钉死。
 */
class TicketPageTest {

    private static Ticket ticket(String id, String content) {
        return Ticket.create(
                id,
                UUID.randomUUID(),
                "Alice",
                List.of(new TicketTarget(null, "Bob"), TicketTarget.byName("Carol")),
                "cheating",
                content,
                "第一行\n第二行",
                1_700_000_000_000L);
    }

    @Test
    @DisplayName("JSON 往返保持全部字段")
    void roundTripKeepsEverything() {
        String id = TicketId.newId();
        Ticket t = ticket(id, "他用了透视");
        TicketPage page = new TicketPage("open", 2, 10, 42, List.of(TicketPage.Entry.of(t)));

        TicketPage back = TicketPage.fromJson(page.toJsonString()).orElseThrow();

        assertEquals("open", back.filter());
        assertEquals(2, back.page());
        assertEquals(10, back.pageSize());
        assertEquals(42, back.total());
        assertEquals(1, back.tickets().size());

        TicketPage.Entry e = back.tickets().get(0);
        assertEquals(id, e.id());
        assertEquals("open", e.status());
        assertEquals("Alice", e.reporter());
        assertEquals("Bob, Carol", e.targets());
        assertEquals("cheating", e.category());
        assertEquals("他用了透视", e.content());
        assertEquals("第一行\n第二行", e.detail(), "多行描述不能被编解码吃掉换行");
        assertEquals(1_700_000_000_000L, e.createdAt());
    }

    @Test
    @DisplayName("空页也能往返（面板要能显示「没有符合条件的工单」）")
    void emptyPageRoundTrip() {
        TicketPage page = new TicketPage("closed", 1, 10, 0, List.of());
        TicketPage back = TicketPage.fromJson(page.toJsonString()).orElseThrow();
        assertTrue(back.isEmpty());
        assertEquals(0, back.total());
        assertEquals(1, back.pages(), "总页数至少为 1，便于界面显示「第 1/1 页」");
    }

    @Test
    @DisplayName("页数按 total/pageSize 上取整")
    void pageCountRounding() {
        assertEquals(1, new TicketPage("all", 1, 10, 0, List.of()).pages());
        assertEquals(1, new TicketPage("all", 1, 10, 10, List.of()).pages());
        assertEquals(2, new TicketPage("all", 1, 10, 11, List.of()).pages());
        assertEquals(5, new TicketPage("all", 1, 10, 50, List.of()).pages());
    }

    @Test
    @DisplayName("坏输入降级为空而不是抛异常（面板打不开比面板空着更糟）")
    void badJsonDegrades() {
        assertTrue(TicketPage.fromJson(null).isEmpty());
        assertTrue(TicketPage.fromJson("").isEmpty());
        assertTrue(TicketPage.fromJson("not json").isEmpty());
        assertTrue(TicketPage.fromJson("[]").isEmpty(), "根节点必须是对象");
        assertTrue(TicketPage.fromJson("{}").isPresent(), "空对象是一页空数据，不是坏数据");
    }

    @Test
    @DisplayName("缺少身份的条目被跳过（无从操作的条目不该出现在面板上）")
    void entriesWithoutIdSkipped() {
        String json = "{\"filter\":\"all\",\"total\":2,\"tickets\":["
                + "{\"reporter\":\"NoId\"},"
                + "{\"id\":\"abc\",\"reporter\":\"HasId\"}]}";
        TicketPage page = TicketPage.fromJson(json).orElseThrow();
        assertEquals(1, page.tickets().size());
        assertEquals("abc", page.tickets().get(0).id());
    }

    @Test
    @DisplayName("缺省字段有安全默认值（服务端少发一个字段不该让面板崩）")
    void missingFieldsHaveDefaults() {
        TicketPage page = TicketPage.fromJson("{\"tickets\":[{\"id\":\"x\"}]}").orElseThrow();
        assertEquals("all", page.filter());
        assertEquals(1, page.page());
        assertEquals(10, page.pageSize(), "没给 pageSize 时用默认值");
        // total 缺失时回退为条目数（而不是 0）：否则界面会出现「共 0 条」却列着一条工单
        assertEquals(1, page.total());
        TicketPage.Entry e = page.tickets().get(0);
        assertEquals("open", e.status());
        assertEquals("", e.reporter());
        assertEquals(0L, e.createdAt());
    }

    @Test
    @DisplayName("total 缺失时回退为条目数（避免界面显示「共 0 条」却有内容）")
    void totalFallsBackToEntryCount() {
        TicketPage page = TicketPage.fromJson("{\"tickets\":[{\"id\":\"a\"},{\"id\":\"b\"}]}")
                .orElseThrow();
        assertEquals(2, page.total());
    }

    @Test
    @DisplayName("构造时归一化非法数值（页号/页大小/总数不得为负或零）")
    void constructorNormalises() {
        TicketPage page = new TicketPage(null, 0, 0, -5, null);
        assertEquals("all", page.filter());
        assertEquals(1, page.page());
        assertEquals(1, page.pageSize());
        assertEquals(0, page.total());
        assertTrue(page.tickets().isEmpty(), "null 列表归一为空");
    }

    @Test
    @DisplayName("Entry 不含短号字段——短号由 id 现算，避免两处算法不一致")
    void entryDoesNotCarryShortId() {
        // 短号是显示用的派生值。若也下发一份，服务端与客户端对「短号怎么算」就有两个答案，
        // 迟早出现「面板显示的短号与命令里用的短号对不上」。
        // 用反射查字段名而非 toString：短号本来就是完整 id 的前缀，字符串包含判断没有意义。
        for (java.lang.reflect.Field f : TicketPage.Entry.class.getDeclaredFields()) {
            if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
            String n = f.getName().toLowerCase(java.util.Locale.ROOT);
            assertFalse(n.contains("short") || n.contains("label"), "Entry 不该携带派生字段: " + f.getName());
        }
        String id = TicketId.newId();
        assertEquals(id, TicketPage.Entry.of(ticket(id, "c")).id());
        assertEquals(TicketId.label(id), TicketId.label(id));
    }

    @Test
    @DisplayName("未受理的工单 handler/note 为空串而不是 null（界面不必到处判空）")
    void unhandledFieldsAreEmptyStrings() {
        TicketPage.Entry e = TicketPage.Entry.of(ticket(TicketId.newId(), "c"));
        assertEquals("", e.handler());
        assertEquals("", e.note());
    }

    @Test
    @DisplayName("已受理工单带上受理人（处理中状态也要显示谁在处理）")
    void handledTicketCarriesHandler() {
        Ticket t = ticket(TicketId.newId(), "c").withStatus(TicketStatus.CLAIMED, "Admin", 5L, "看了一下");
        TicketPage.Entry e = TicketPage.Entry.of(t);
        assertEquals("claimed", e.status());
        assertEquals("Admin", e.handler());
        assertEquals("看了一下", e.note());
    }

    @Test
    @DisplayName("筛选字面量解析：all 与未知值都表示不过滤")
    void filterParsing() {
        assertNull(TicketService.parseFilter("all"));
        assertNull(TicketService.parseFilter(null));
        assertNull(TicketService.parseFilter(""));
        assertNull(TicketService.parseFilter("bogus"), "坏筛选值按「全部」处理，不该让面板打不开");
        assertEquals(TicketStatus.OPEN, TicketService.parseFilter("open"));
        assertEquals(TicketStatus.CLOSED, TicketService.parseFilter("CLOSED"));
        assertEquals(TicketStatus.REJECTED, TicketService.parseFilter(" rejected "));
    }

    @Test
    @DisplayName("面板筛选集合与状态枚举一一对应（漏一个筛选项就找不到那批工单）")
    void filtersCoverAllStatuses() {
        for (TicketStatus st : TicketStatus.values()) {
            assertTrue(TicketService.FILTERS.contains(st.id()), "缺少筛选项: " + st.id());
        }
        assertTrue(TicketService.FILTERS.contains("all"));
    }
}
