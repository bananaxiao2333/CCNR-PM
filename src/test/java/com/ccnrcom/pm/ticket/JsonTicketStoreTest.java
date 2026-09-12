/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.pm.ticket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 文件工单仓储测试（端到端：原子写盘 → 重新打开 → 数据与标识都还在）。
 *
 * <p>这是「未配置数据库」的默认路径，所以它必须真的可靠：玩家提交成功＝已经落盘。
 */
class JsonTicketStoreTest {

    private static TicketStore.NewTicket sample(UUID reporter, List<String> targets, String category) {
        return new TicketStore.NewTicket(
                reporter,
                "Alice",
                targets.stream().map(TicketTarget::byName).toList(),
                category,
                "他用了透视",
                "在矿洞里隔墙挖矿",
                1_700_000_000_000L);
    }

    private static List<String> one(String name) {
        return List.of(name);
    }

    @Test
    @DisplayName("每条工单都有互不相同的 UUID 身份")
    void assignsUniqueIds(@TempDir Path dir) {
        Path file = dir.resolve("tickets.json");
        JsonTicketStore store = new JsonTicketStore(file);
        assertTrue(store.init());

        UUID reporter = UUID.randomUUID();
        Ticket first = store.insert(sample(reporter, one("Bob"), "cheating")).orElseThrow();
        Ticket second = store.insert(sample(reporter, one("Carol"), "spam")).orElseThrow();

        assertTrue(TicketId.isFullId(first.id()), "身份必须是合法 UUID");
        assertNotEquals(first.id(), second.id(), "两张工单的标识必须不同");
        assertEquals(TicketStatus.OPEN, first.status(), "新建工单必须是待处理");
        assertTrue(Files.isRegularFile(file), "提交后必须已经落盘");
    }

    @Test
    @DisplayName("重新打开后数据与标识都延续（不会覆盖、不会换 id）")
    void survivesReopen(@TempDir Path dir) {
        Path file = dir.resolve("tickets.json");
        UUID reporter = UUID.randomUUID();

        JsonTicketStore first = new JsonTicketStore(file);
        first.init();
        String id = first.insert(sample(reporter, one("Bob"), "cheating"))
                .orElseThrow()
                .id();
        first.close();

        JsonTicketStore second = new JsonTicketStore(file);
        assertTrue(second.init());
        assertEquals(1, second.count(null), "重启后应读回原有工单");
        assertEquals(id, second.byId(id).orElseThrow().id(), "重启后 id 必须原样保留");
        second.close();
    }

    @Test
    @DisplayName("按 id 精确查询；大小写与连字符均可容错")
    void byIdLookup(@TempDir Path dir) {
        JsonTicketStore store = new JsonTicketStore(dir.resolve("tickets.json"));
        store.init();
        String id = store.insert(sample(UUID.randomUUID(), one("Bob"), "cheating"))
                .orElseThrow()
                .id();

        assertTrue(store.byId(id).isPresent());
        assertTrue(store.byId(id.toUpperCase(java.util.Locale.ROOT)).isPresent(), "大写也应命中");
        assertTrue(store.byId(id.replace("-", "")).isPresent(), "去掉连字符也应命中");
        assertTrue(store.byId("no-such-id").isEmpty());
        assertTrue(store.byId(null).isEmpty());
    }

    @Test
    @DisplayName("按短号前缀查询：唯一时返回一条，歧义时返回多条（由上层报错）")
    void byIdPrefixLookup(@TempDir Path dir) {
        JsonTicketStore store = new JsonTicketStore(dir.resolve("tickets.json"));
        store.init();
        String id = store.insert(sample(UUID.randomUUID(), one("Bob"), "cheating"))
                .orElseThrow()
                .id();
        String shortId = TicketId.shortId(id);

        List<Ticket> hit = store.byIdPrefix(shortId, 2);
        assertEquals(1, hit.size());
        assertEquals(id, hit.get(0).id());

        // 只要不是空查询，任何更短的前缀都应能命中这一条
        assertFalse(store.byIdPrefix(shortId.substring(0, 4), 2).isEmpty());
        assertTrue(store.byIdPrefix("ffff", 2).isEmpty(), "不存在的短号不应命中");
        assertTrue(store.byIdPrefix("", 2).isEmpty());
        assertTrue(store.byIdPrefix(null, 2).isEmpty());
    }

    @Test
    @DisplayName("关联玩家的多个名字与 UUID 都能落盘并读回")
    void targetsRoundTrip(@TempDir Path dir) {
        Path file = dir.resolve("tickets.json");
        UUID targetUuid = UUID.randomUUID();
        JsonTicketStore store = new JsonTicketStore(file);
        store.init();
        String id = store.insert(new TicketStore.NewTicket(
                        UUID.randomUUID(),
                        "Alice",
                        List.of(new TicketTarget(targetUuid, "Bob"), TicketTarget.byName("Carol")),
                        "cheating",
                        "内容",
                        "细节",
                        1L))
                .orElseThrow()
                .id();
        store.close();

        JsonTicketStore reopened = new JsonTicketStore(file);
        reopened.init();
        Ticket t = reopened.byId(id).orElseThrow();
        assertEquals(2, t.targets().size());
        assertEquals(targetUuid, t.targets().get(0).uuid());
        assertEquals("Bob", t.targets().get(0).name());
        assertEquals(null, t.targets().get(1).uuid(), "离线玩家只留名字");
        assertEquals("Bob, Carol", t.targetLabel());
    }

    @Test
    @DisplayName("没有关联玩家的工单也能正常落盘（该字段选填）")
    void emptyTargetsPersist(@TempDir Path dir) {
        Path file = dir.resolve("tickets.json");
        JsonTicketStore store = new JsonTicketStore(file);
        store.init();
        String id = store.insert(sample(UUID.randomUUID(), List.of(), "other"))
                .orElseThrow()
                .id();
        store.close();

        JsonTicketStore reopened = new JsonTicketStore(file);
        reopened.init();
        Ticket t = reopened.byId(id).orElseThrow();
        assertTrue(t.targets().isEmpty());
        assertEquals("", t.targetLabel());
    }

    @Test
    @DisplayName("多行详细描述（含换行）原样保留")
    void multilineDetailPersists(@TempDir Path dir) {
        Path file = dir.resolve("tickets.json");
        String detail = "第一行\n第二行\n\n第四行";
        JsonTicketStore store = new JsonTicketStore(file);
        store.init();
        String id = store.insert(
                        new TicketStore.NewTicket(UUID.randomUUID(), "Alice", List.of(), "other", "内容", detail, 1L))
                .orElseThrow()
                .id();
        store.close();

        JsonTicketStore reopened = new JsonTicketStore(file);
        reopened.init();
        assertEquals(detail, reopened.byId(id).orElseThrow().detail(), "多行描述的换行不能被吃掉");
    }

    @Test
    @DisplayName("按状态计数、按举报人统计未办结、最近提交时间")
    void queriesWork(@TempDir Path dir) {
        JsonTicketStore store = new JsonTicketStore(dir.resolve("tickets.json"));
        store.init();
        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();

        String aId = store.insert(sample(alice, one("Bob"), "cheating"))
                .orElseThrow()
                .id();
        store.insert(sample(alice, one("Carol"), "spam"));
        store.insert(sample(bob, one("Dave"), "other"));

        assertEquals(3, store.count(null));
        assertEquals(3, store.count(TicketStatus.OPEN));
        assertEquals(0, store.count(TicketStatus.CLOSED));
        assertEquals(2, store.countUnresolvedByReporter(alice));
        assertEquals(aId, store.byId(aId).orElseThrow().id());
    }

    @Test
    @DisplayName("最近提交时间用于冷却判定")
    void tracksLastSubmit(@TempDir Path dir) {
        JsonTicketStore store = new JsonTicketStore(dir.resolve("tickets.json"));
        store.init();
        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();

        assertEquals(0L, store.lastSubmitAt(alice), "没提交过应返回 0");
        store.insert(sample(alice, one("Bob"), "cheating"));
        assertEquals(1_700_000_000_000L, store.lastSubmitAt(alice));
        assertEquals(0L, store.lastSubmitAt(bob), "别的玩家不受影响");
    }

    @Test
    @DisplayName("状态迁移落盘且可再次读回")
    void statusTransitionPersists(@TempDir Path dir) {
        Path file = dir.resolve("tickets.json");
        JsonTicketStore store = new JsonTicketStore(file);
        store.init();
        String id = store.insert(sample(UUID.randomUUID(), one("Bob"), "cheating"))
                .orElseThrow()
                .id();

        assertTrue(store.updateStatus(id, TicketStatus.CLOSED, "Admin", 1_700_000_100_000L, "已处理"));
        Ticket t = store.byId(id).orElseThrow();
        assertEquals(TicketStatus.CLOSED, t.status());
        assertEquals("Admin", t.handlerName());
        assertEquals("已处理", t.handleNote());
        assertFalse(t.status().isUnresolved(), "办结后不再占用受理队列");
        store.close();

        JsonTicketStore reopened = new JsonTicketStore(file);
        reopened.init();
        assertEquals(TicketStatus.CLOSED, reopened.byId(id).orElseThrow().status());
        assertFalse(reopened.updateStatus("no-such-id", TicketStatus.CLOSED, "Admin", 0L, null), "不存在的标识不应假报成功");
    }

    @Test
    @DisplayName("列表按时间倒序，支持状态过滤与分页")
    void listOrderAndPaging(@TempDir Path dir) {
        JsonTicketStore store = new JsonTicketStore(dir.resolve("tickets.json"));
        store.init();
        UUID r = UUID.randomUUID();
        String firstId = null;
        for (int i = 0; i < 5; i++) {
            String id = store.insert(
                            new TicketStore.NewTicket(r, "Alice", List.of(), "cheating", "c" + i, "", 1_000L + i))
                    .orElseThrow()
                    .id();
            if (i == 0) firstId = id;
        }
        store.updateStatus(firstId, TicketStatus.REJECTED, "Admin", 0L, null);

        List<Ticket> newest = store.list(null, 2, 0);
        assertEquals(2, newest.size());
        assertEquals("c4", newest.get(0).content(), "最新提交应排在最前");
        assertEquals("c3", newest.get(1).content());

        List<Ticket> page2 = store.list(null, 2, 2);
        assertEquals("c2", page2.get(0).content());

        List<Ticket> rejected = store.list(TicketStatus.REJECTED, 10, 0);
        assertEquals(1, rejected.size());
        assertEquals(firstId, rejected.get(0).id());
    }

    @Test
    @DisplayName("文件损坏时备份 .bak 并以空表继续，不影响服务启动")
    void corruptedFileIsBackedUp(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("tickets.json");
        Files.writeString(file, "{ this is not json");

        JsonTicketStore store = new JsonTicketStore(file);
        assertTrue(store.init(), "损坏文件不应让初始化失败");
        assertEquals(0, store.count(null));
        assertTrue(Files.isRegularFile(dir.resolve("tickets.json.bak")), "损坏内容必须留下 .bak 以便人工比对");
    }

    @Test
    @DisplayName("首次启动文件不存在是正常情况")
    void missingFileIsFine(@TempDir Path dir) {
        JsonTicketStore store = new JsonTicketStore(dir.resolve("nested").resolve("tickets.json"));
        assertTrue(store.init());
        Optional<Ticket> created = store.insert(sample(UUID.randomUUID(), one("Bob"), "cheating"));
        assertTrue(created.isPresent(), "目录不存在时应自动创建目录并成功写入");
    }
}
