/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.pm.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** SQL 方言测试：upsert 语法分支，以及「全部列都是主键」时快速失败。 */
class SqlDialectTest {

    private static final List<String> COLS = List.of("id", "seq", "status");

    private static final List<String> PK = List.of("id");

    @Test
    @DisplayName("标识符引号与占位符两端一致")
    void quotingAndPlaceholders() {
        SqlDialect d = new SqlDialect(DbType.SQLITE);
        assertEquals("`tickets`", d.quote("tickets"));
        assertEquals("?, ?, ?", d.placeholders(3));
        assertEquals("", d.placeholders(0));
    }

    @Test
    @DisplayName("SQLite 走 ON CONFLICT DO UPDATE")
    void sqliteUpsert() {
        String sql = new SqlDialect(DbType.SQLITE).upsert("t", COLS, PK);
        assertTrue(sql.startsWith("INSERT INTO `t` (`id`, `seq`, `status`) VALUES (?, ?, ?)"), sql);
        assertTrue(sql.contains("ON CONFLICT(`id`) DO UPDATE SET"), sql);
        assertTrue(sql.contains("`seq`=excluded.`seq`"), sql);
    }

    @Test
    @DisplayName("MySQL 走 ON DUPLICATE KEY UPDATE")
    void mysqlUpsert() {
        String sql = new SqlDialect(DbType.MYSQL).upsert("t", COLS, PK);
        assertTrue(sql.contains("ON DUPLICATE KEY UPDATE"), sql);
        assertTrue(sql.contains("`status`=VALUES(`status`)"), sql);
    }

    @Test
    @DisplayName("全部列都是主键时快速失败，而不是生成语法非法的 SQL")
    void allPrimaryKeyColumnsRejected() {
        SqlDialect d = new SqlDialect(DbType.SQLITE);
        List<String> keys = List.of("a", "b");
        assertThrows(IllegalArgumentException.class, () -> d.upsert("t", keys, keys));
    }

    @Test
    @DisplayName("后端类型解析大小写不敏感，未知值返回 null")
    void typeParsing() {
        assertEquals(DbType.SQLITE, DbType.parse("SQLite"));
        assertEquals(DbType.MYSQL, DbType.parse("  mysql "));
        assertEquals(null, DbType.parse("postgres"));
        assertEquals(null, DbType.parse(null));
    }

    @Test
    @DisplayName("JDBC URL 与驱动类按后端切换")
    void jdbcTargets() {
        assertTrue(DbConfig.disabled().jdbcUrl().startsWith("jdbc:sqlite:"));
        assertEquals("org.sqlite.JDBC", DbConfig.disabled().driverClass());

        DbConfig mysql = new DbConfig(true, DbType.MYSQL, "", "db.example", 3307, "ccnr", "u", "p");
        assertTrue(mysql.jdbcUrl().startsWith("jdbc:mysql://db.example:3307/ccnr"), mysql.jdbcUrl());
        assertEquals("com.mysql.cj.jdbc.Driver", mysql.driverClass());
        assertTrue(mysql.describe().contains("mysql://db.example:3307/ccnr"));
    }

    @Test
    @DisplayName("未配置时默认关闭数据库，走文件后端")
    void defaultsToFileBackend() {
        DbConfig c = DbConfig.disabled();
        assertTrue(!c.enabled(), "默认必须是关闭的，单机服零部署即可用");
        assertEquals(DbType.SQLITE, c.mode());
    }
}
