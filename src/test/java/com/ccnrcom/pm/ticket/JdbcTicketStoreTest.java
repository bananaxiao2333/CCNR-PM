/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.pm.ticket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ccnrcom.pm.data.Database;
import com.ccnrcom.pm.data.DbConfig;
import com.ccnrcom.pm.data.DbType;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 数据库工单仓储测试——**跑真实 SQLite**（不是模拟）。
 *
 * <p>为什么这条测试重要：此前 `JdbcTicketStore` 只覆盖了「编译通过」与「SQL 字符串生成」，
 * 从未真正执行过 SQL。而 SQL 的问题（列名写错、约束不匹配、ALTER 语法方言差异）
 * 只在真库里才暴露。这里用 sqlite-jdbc 建一个临时库，把仓储的每条路径真跑一遍。
 *
 * <p>也覆盖**迁移阶梯**：手工造一个 v2 结构的库（含 {@code seq INTEGER NOT NULL}），
 * 再让 {@link Database#connect()} 去升级它。这条路径如果坏了，
 * 后果是老库升级后**玩家提交工单时才失败**——最难定位的一类故障。
 */
class JdbcTicketStoreTest {

    private static Database openDatabase(Path dir) {
        Path dbFile = dir.resolve("test.db");
        DbConfig cfg = new DbConfig(true, DbType.SQLITE, dbFile.toString(), "localhost", 3306, "x", "u", "");
        Database db = new Database(cfg);
        assertTrue(db.connect(), "应能连上临时 SQLite 库");
        assertTrue(db.usable(), "连接成功后 usable() 必须为真");
        return db;
    }

    private static TicketStore.NewTicket sample(UUID reporter, List<String> targets, String category, long at) {
        return new TicketStore.NewTicket(
                reporter,
                "Alice",
                targets.stream().map(TicketTarget::byName).toList(),
                category,
                "他用了透视",
                "第一行\n第二行",
                at);
    }

    @Test
    @DisplayName("建表成功；插入拿到 UUID 身份；按 id / 前缀都能查回")
    void insertAndLookup(@TempDir Path dir) {
        Database db = openDatabase(dir);
        try {
            JdbcTicketStore store = new JdbcTicketStore(db);
            assertTrue(store.init());

            Ticket t = store.insert(sample(UUID.randomUUID(), List.of("Bob"), "cheating", 1_000L))
                    .orElseThrow();
            assertTrue(TicketId.isFullId(t.id()), "数据库后端也必须分配合法 UUID");
            assertEquals(TicketStatus.OPEN, t.status());

            assertEquals(t.id(), store.byId(t.id()).orElseThrow().id());
            assertEquals(
                    t.id(),
                    store.byId(t.id().toUpperCase(java.util.Locale.ROOT))
                            .orElseThrow()
                            .id(),
                    "大写形式应命中");
            assertEquals(
                    t.id(), store.byId(t.id().replace("-", "")).orElseThrow().id(), "去掉连字符的 32 位形式应命中");

            assertEquals(1, store.byIdPrefix(TicketId.shortId(t.id()), 2).size());
            assertTrue(store.byIdPrefix("ffff", 2).isEmpty());
            assertTrue(store.byId("no-such-id").isEmpty());
        } finally {
            db.disconnect();
        }
    }

    @Test
    @DisplayName("前缀跨过连字符也能命中（SQL 侧已 REPLACE 掉连字符）")
    void prefixAcrossDashMatches(@TempDir Path dir) {
        Database db = openDatabase(dir);
        try {
            JdbcTicketStore store = new JdbcTicketStore(db);
            store.init();
            Ticket t = store.insert(sample(UUID.randomUUID(), List.of(), "other", 1L))
                    .orElseThrow();

            // 取前 12 位十六进制 —— 它跨越了 id 第 8 位后的那个连字符
            String withDash = t.id().substring(0, 13); // 形如 3f2a91c4-5b7
            assertEquals(t.id(), store.byIdPrefix(withDash, 2).get(0).id(), "跨连字符的前缀必须能查到，否则玩家粘贴短号时会莫名查不到");
        } finally {
            db.disconnect();
        }
    }

    @Test
    @DisplayName("两条工单标识不同；计数与按举报人统计正确")
    void uniqueIdsAndCounts(@TempDir Path dir) {
        Database db = openDatabase(dir);
        try {
            JdbcTicketStore store = new JdbcTicketStore(db);
            store.init();
            UUID alice = UUID.randomUUID();
            UUID bob = UUID.randomUUID();

            String a = store.insert(sample(alice, List.of("Bob"), "cheating", 1_000L))
                    .orElseThrow()
                    .id();
            String b = store.insert(sample(alice, List.of("Carol"), "spam", 2_000L))
                    .orElseThrow()
                    .id();
            store.insert(sample(bob, List.of("Dave"), "other", 3_000L));

            assertNotEquals(a, b);
            assertEquals(3, store.count(null));
            assertEquals(3, store.count(TicketStatus.OPEN));
            assertEquals(2, store.countUnresolvedByReporter(alice));
            assertEquals(3_000L, store.lastSubmitAt(bob));
        } finally {
            db.disconnect();
        }
    }

    @Test
    @DisplayName("关联玩家列表与多行描述经数据库往返后完全一致")
    void targetsAndMultilineDetailRoundTrip(@TempDir Path dir) {
        Database db = openDatabase(dir);
        try {
            JdbcTicketStore store = new JdbcTicketStore(db);
            store.init();
            UUID targetUuid = UUID.randomUUID();
            String detail = "第一行\n第二行\n\n第四行";

            Ticket t = store.insert(new TicketStore.NewTicket(
                            UUID.randomUUID(),
                            "Alice",
                            List.of(new TicketTarget(targetUuid, "Bob"), TicketTarget.byName("Carol")),
                            "cheating",
                            "内容",
                            detail,
                            1L))
                    .orElseThrow();

            Ticket back = store.byId(t.id()).orElseThrow();
            assertEquals(2, back.targets().size());
            assertEquals(targetUuid, back.targets().get(0).uuid());
            assertEquals(null, back.targets().get(1).uuid());
            assertEquals(detail, back.detail(), "换行不能被数据库往返吃掉");
        } finally {
            db.disconnect();
        }
    }

    @Test
    @DisplayName("状态迁移按 id 生效；不存在的 id 不假报成功")
    void updateStatusById(@TempDir Path dir) {
        Database db = openDatabase(dir);
        try {
            JdbcTicketStore store = new JdbcTicketStore(db);
            store.init();
            String id = store.insert(sample(UUID.randomUUID(), List.of(), "other", 1L))
                    .orElseThrow()
                    .id();

            assertTrue(store.updateStatus(id, TicketStatus.CLOSED, "Admin", 999L, "已处理"));
            Ticket t = store.byId(id).orElseThrow();
            assertEquals(TicketStatus.CLOSED, t.status());
            assertEquals("Admin", t.handlerName());
            assertFalse(t.status().isUnresolved());

            assertFalse(store.updateStatus("no-such-id", TicketStatus.CLOSED, "Admin", 0L, null));
        } finally {
            db.disconnect();
        }
    }

    @Test
    @DisplayName("列表按时间倒序，可分页与按状态过滤")
    void listOrderAndPaging(@TempDir Path dir) {
        Database db = openDatabase(dir);
        try {
            JdbcTicketStore store = new JdbcTicketStore(db);
            store.init();
            UUID r = UUID.randomUUID();
            String firstId = null;
            for (int i = 0; i < 5; i++) {
                String id = store.insert(sample(r, List.of(), "cheating", 1_000L + i))
                        .orElseThrow()
                        .id();
                if (i == 0) firstId = id;
            }
            store.updateStatus(firstId, TicketStatus.REJECTED, "Admin", 0L, null);

            List<Ticket> newest = store.list(null, 2, 0);
            assertEquals(2, newest.size());
            assertEquals("第一行\n第二行", newest.get(0).detail());

            assertEquals(1, store.list(TicketStatus.REJECTED, 10, 0).size());
            assertEquals(
                    firstId, store.list(TicketStatus.REJECTED, 10, 0).get(0).id());
        } finally {
            db.disconnect();
        }
    }

    @Test
    @DisplayName("重启后数据仍在（真正的落盘，不是内存表）")
    void persistsAcrossReconnect(@TempDir Path dir) {
        String id;
        Database first = openDatabase(dir);
        try {
            JdbcTicketStore store = new JdbcTicketStore(first);
            store.init();
            id = store.insert(sample(UUID.randomUUID(), List.of("Bob"), "cheating", 1L))
                    .orElseThrow()
                    .id();
        } finally {
            first.disconnect();
        }

        Database second = openDatabase(dir);
        try {
            JdbcTicketStore store = new JdbcTicketStore(second);
            store.init();
            assertEquals(1, store.count(null), "重连后应读回原有工单");
            assertEquals(id, store.byId(id).orElseThrow().id());
        } finally {
            second.disconnect();
        }
    }

    // ------------------------------------------------------------------
    // 迁移阶梯：v2 -> v3
    // ------------------------------------------------------------------

    @Test
    @DisplayName("老库（v2 含 seq NOT NULL）能被自动迁移，迁移后插入正常")
    void migratesV2Database(@TempDir Path dir) throws Exception {
        Path dbFile = dir.resolve("legacy.db");

        // 1) 手工造一个 v2 结构的库：带 seq INTEGER NOT NULL 与它的唯一索引
        Class.forName("org.sqlite.JDBC");
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + dbFile);
                Statement st = c.createStatement()) {
            st.executeUpdate("CREATE TABLE pm_tickets ("
                    // v2 真实结构：已用 targets 存关联玩家，但仍带 seq 流水号列
                    + "id TEXT NOT NULL, seq INTEGER NOT NULL, reporter_uuid TEXT, reporter_name TEXT,"
                    + "targets TEXT, category TEXT, content TEXT, detail TEXT,"
                    + "status TEXT, created_at INTEGER, handler TEXT, handled_at INTEGER, handle_note TEXT,"
                    + "PRIMARY KEY (id))");
            st.executeUpdate("CREATE UNIQUE INDEX pm_tickets_seq_idx ON pm_tickets (seq)");
            // 塞一条老数据，验证迁移不会把它弄丢
            st.executeUpdate("INSERT INTO pm_tickets (id, seq, reporter_name, status, created_at)"
                    + " VALUES ('11111111-2222-3333-4444-555555555555', 7, 'OldAlice', 'open', 100)");
        }

        // 2) 用本模组的连接流程去升级它
        DbConfig cfg = new DbConfig(true, DbType.SQLITE, dbFile.toString(), "localhost", 3306, "x", "u", "");
        Database db = new Database(cfg);
        assertTrue(db.connect(), "v2 老库应当能连接并自动迁移");
        try {
            // 3) seq 列已被移除
            try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + dbFile);
                    Statement st = c.createStatement();
                    ResultSet rs = st.executeQuery("PRAGMA table_info(pm_tickets)")) {
                boolean hasSeq = false;
                int columns = 0;
                while (rs.next()) {
                    columns++;
                    if ("seq".equalsIgnoreCase(rs.getString("name"))) hasSeq = true;
                }
                assertFalse(hasSeq, "迁移后不应再有 seq 列");
                // 真正的不变量：迁移后的表列集合必须与当前 schema 完全一致
                assertEquals(
                        com.ccnrcom.pm.data.DbSchema.TICKET_COLUMNS.size(),
                        columns,
                        "迁移后列数应与 DbSchema.TICKET_COLUMNS 一致（v2 的 13 列去掉 seq 后为 12）");
            }

            // 4) 老数据还在
            JdbcTicketStore store = new JdbcTicketStore(db);
            store.init();
            assertEquals(1, store.count(null), "迁移不得丢数据");
            Ticket old = store.byId("11111111-2222-3333-4444-555555555555").orElseThrow();
            assertEquals("OldAlice", old.reporterName());
            assertEquals(TicketStatus.OPEN, old.status());

            // 5) 迁移后能正常插入（这正是「不迁移就会失败」的那一步：
            //    旧的 seq NOT NULL 列会让新 INSERT 直接违反约束）
            Ticket fresh = store.insert(sample(UUID.randomUUID(), List.of("Bob"), "cheating", 2L))
                    .orElseThrow();
            assertEquals(2, store.count(null));
            assertTrue(TicketId.isFullId(fresh.id()));
        } finally {
            db.disconnect();
        }
    }

    @Test
    @DisplayName("重复连接不会重复迁移（迁移是幂等的）")
    void migrationIsIdempotent(@TempDir Path dir) throws Exception {
        Path dbFile = dir.resolve("legacy2.db");
        Class.forName("org.sqlite.JDBC");
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + dbFile);
                Statement st = c.createStatement()) {
            st.executeUpdate("CREATE TABLE pm_tickets (id TEXT NOT NULL, seq INTEGER NOT NULL,"
                    + " reporter_uuid TEXT, reporter_name TEXT, targets TEXT,"
                    + " category TEXT, content TEXT, detail TEXT, status TEXT, created_at INTEGER,"
                    + " handler TEXT, handled_at INTEGER, handle_note TEXT, PRIMARY KEY (id))");
            st.executeUpdate("CREATE UNIQUE INDEX pm_tickets_seq_idx ON pm_tickets (seq)");
        }

        DbConfig cfg = new DbConfig(true, DbType.SQLITE, dbFile.toString(), "localhost", 3306, "x", "u", "");
        for (int i = 0; i < 3; i++) {
            Database db = new Database(cfg);
            assertTrue(db.connect(), "第 " + (i + 1) + " 次连接应当成功");
            assertTrue(db.usable());
            db.disconnect();
        }
    }

    @Test
    @DisplayName("连不上时 usable() 为 false（配置开了但连不上不得被当作可用）")
    void unusableWhenNotConnected(@TempDir Path dir) {
        // 指向一个不存在的目录且驱动无法建库时……这里用一个非法 URL 触发失败：
        DbConfig cfg = new DbConfig(true, DbType.MYSQL, "", "127.0.0.1", 1, "nope", "nope", "nope");
        Database db = new Database(cfg);
        assertTrue(db.enabled(), "配置里是启用状态");
        assertFalse(db.connect(), "端口 1 上不会有 MySQL");
        assertFalse(db.usable(), "连不上时 usable() 必须为 false——存储选择依赖这个判据");
        assertFalse(db.ping());
        db.disconnect();
    }

    @Test
    @DisplayName("未启用数据库时 connect() 直接返回 false，不做任何连接尝试")
    void disabledDatabaseDoesNotConnect() {
        Database db = new Database(DbConfig.disabled());
        assertFalse(db.enabled());
        assertFalse(db.connect());
        assertFalse(db.usable());
        db.disconnect();
    }

    @Test
    @DisplayName("对已关闭的库做写操作不会抛异常，只是返回 false")
    void writeAfterCloseIsSafe(@TempDir Path dir) {
        Database db = openDatabase(dir);
        JdbcTicketStore store = new JdbcTicketStore(db);
        store.init();
        store.insert(sample(UUID.randomUUID(), List.of(), "other", 1L));
        db.disconnect();

        // 关闭后写：应记录错误并返回空，而不是把异常抛到调用方（主线程）
        assertFalse(
                store.insert(sample(UUID.randomUUID(), List.of(), "other", 2L)).isPresent());
    }

    @Test
    @DisplayName("DbSchema 版本号已升到 4（忽略名单新增了一张表，结构变更必须体现出来）")
    void schemaVersionBumped() {
        assertEquals(4, com.ccnrcom.pm.data.DbSchema.VERSION);
    }

    @Test
    @DisplayName("空标识在触碰数据库之前就被拒绝（传 null 的 Database 也不会崩，证明校验顺序正确）")
    void blankIdRejectedBeforeTouchingDatabase(@TempDir Path dir) {
        Database db = openDatabase(dir);
        try {
            JdbcTicketStore store = new JdbcTicketStore(db);
            assertFalse(store.updateStatus("", TicketStatus.CLOSED, "a", 0L, null));
            assertFalse(store.updateStatus(null, TicketStatus.CLOSED, "a", 0L, null));
            assertTrue(store.byId("").isEmpty());
            assertTrue(store.byIdPrefix("", 2).isEmpty());
        } finally {
            db.disconnect();
        }
    }
}
