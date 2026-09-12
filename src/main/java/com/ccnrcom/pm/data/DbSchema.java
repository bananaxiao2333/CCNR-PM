/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.pm.data;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * 建表。
 *
 * <p>迁移策略：只做 {@code CREATE TABLE IF NOT EXISTS} + {@code CREATE INDEX IF NOT EXISTS}。
 * 这两条都是幂等的，每次连接都跑一遍即可。
 *
 * <p>**边界（务必知悉）**：本类**不支持给已有表加列**。若未来要改 {@code pm_tickets} 的列结构，
 * 必须在此新增一段「读 {@code pm_schema_version} → 版本低于 N 则 ALTER」的阶梯，
 * 否则老库不会自动获得新列，而新代码会在 SELECT 时炸掉。
 * v3 同样没有写阶梯——本模组仍处于 0.x 开发期，尚未有需要兼容的线上库。
 *
 * <p>版本历史：
 * <ul>
 *   <li>v1 —— 初版，单一「被举报玩家」（{@code target_uuid} / {@code target_name} 两列）。**未对外发布**。</li>
 *   <li>v2 —— 改为「关联玩家」多值列表（单列 {@code targets}，存 JSON 数组）。
 *       因为 v1 从未发布过，这里直接改列定义而没有写迁移阶梯。</li>
 *   <li>v4 —— 新增 {@code pm_ticket_ignores}（按管理员隔离的忽略名单）。
 *       这是**加表**，靠 {@code CREATE TABLE IF NOT EXISTS} 即可生效，不需要 ALTER 阶梯。</li>
 *   <li>v3 —— 去掉 {@code seq} 流水号列与它的唯一索引：工单身份改为**只用 UUID**
 *       （流水号会随存储清空而重复、也可被枚举，见 {@code TicketId} 的类注释）。
 *       同时不再需要「事务内取 max(seq)+1」，插入路径简化。</li>
 * </ul>
 */
public final class DbSchema {

    private static final Logger LOGGER = LogManager.getLogger("ccnr_pm");

    /** 当前结构版本；v2 起新增列必须同时加迁移阶梯（见类注释的版本历史）。 */
    public static final int VERSION = 4;

    public static final String TABLE_TICKETS = "pm_tickets";

    /** 按管理员隔离的忽略名单：某管理员忽略某工单后，那张卡片不再出现在**他**的看板上。 */
    public static final String TABLE_IGNORES = "pm_ticket_ignores";

    public static final String TABLE_META = "pm_meta";

    /** 工单表列定义（顺序即 SELECT/INSERT 的约定顺序，改动需同步 JdbcTicketStore）。 */
    public static final List<String> TICKET_COLUMNS = List.of(
            "id",
            "reporter_uuid",
            "reporter_name",
            "targets",
            "category",
            "content",
            "detail",
            "status",
            "created_at",
            "handler",
            "handled_at",
            "handle_note");

    private DbSchema() {}

    /**
     * 建表 + 建索引；返回是否全部成功。
     *
     * <p>返回 false 时调用方应视为「数据库不可用」并降级到文件后端（而不是带着半张表继续跑）。
     */
    public static boolean bootstrap(Connection conn, SqlDialect dialect) {
        String tickets = "CREATE TABLE IF NOT EXISTS " + dialect.quote(TABLE_TICKETS) + " ("
                + dialect.quote("id") + " TEXT NOT NULL, "
                + dialect.quote("reporter_uuid") + " TEXT, "
                + dialect.quote("reporter_name") + " TEXT, "
                + dialect.quote("targets") + " TEXT, "
                + dialect.quote("category") + " TEXT, "
                + dialect.quote("content") + " TEXT, "
                + dialect.quote("detail") + " TEXT, "
                + dialect.quote("status") + " TEXT, "
                + dialect.quote("created_at") + " INTEGER, "
                + dialect.quote("handler") + " TEXT, "
                + dialect.quote("handled_at") + " INTEGER, "
                + dialect.quote("handle_note") + " TEXT, "
                + "PRIMARY KEY (" + dialect.quote("id") + "))";

        String meta = "CREATE TABLE IF NOT EXISTS " + dialect.quote(TABLE_META) + " ("
                + dialect.quote("key") + " TEXT NOT NULL, "
                + dialect.quote("value") + " TEXT, "
                + "PRIMARY KEY (" + dialect.quote("key") + "))";

        String ignores = "CREATE TABLE IF NOT EXISTS " + dialect.quote(TABLE_IGNORES) + " ("
                + dialect.quote("admin_uuid") + " TEXT NOT NULL, "
                + dialect.quote("ticket_id") + " TEXT NOT NULL, "
                + dialect.quote("created_at") + " INTEGER, "
                + "PRIMARY KEY (" + dialect.quote("admin_uuid") + ", " + dialect.quote("ticket_id") + "))";

        String statusIdx = "CREATE INDEX IF NOT EXISTS " + dialect.quote("pm_tickets_status_idx") + " ON "
                + dialect.quote(TABLE_TICKETS) + " (" + dialect.quote("status") + ", " + dialect.quote("created_at")
                + ")";

        try (Statement st = conn.createStatement()) {
            st.executeUpdate(tickets);
            st.executeUpdate(meta);
            st.executeUpdate(ignores);
            st.executeUpdate(statusIdx);
        } catch (SQLException e) {
            LOGGER.error("[CCNR-PM] 建表失败: {}", e.toString());
            return false;
        }
        return migrate(conn, dialect);
    }

    /**
     * 迁移阶梯：把老库补到当前结构。
     *
     * <p>存在的意义：{@code CREATE TABLE IF NOT EXISTS} 对**已存在**的表什么都不做，
     * 所以列结构的变化不会自动生效。v2 的 {@code seq INTEGER NOT NULL} 就是一个实例——
     * 若放任不管，v3 的 INSERT 不再提供该列，老库升级后会因 NOT NULL 约束失败，
     * 而且失败发生在**玩家提交工单的那一刻**，而不是启动时（更难被发现）。
     *
     * <p>当前只有一步：v2 → v3 删除 {@code seq} 列。
     *
     * @return 是否成功；false 时调用方应视为数据库不可用并降级
     */
    public static boolean migrate(Connection conn, SqlDialect dialect) {
        if (!hasColumn(conn, TABLE_TICKETS, "seq")) return true;

        // 必须先删索引：SQLite 不允许删除仍被索引引用的列
        try (Statement st = conn.createStatement()) {
            st.executeUpdate(dialect.dropIndex("pm_tickets_seq_idx", TABLE_TICKETS));
        } catch (SQLException e) {
            // 索引不存在（或 MySQL 不支持 DROP INDEX IF EXISTS）——不影响后续删列
            LOGGER.debug("[CCNR-PM] 序号索引本来就不存在: {}", e.toString());
        }

        try (Statement st = conn.createStatement()) {
            st.executeUpdate("ALTER TABLE " + dialect.quote(TABLE_TICKETS) + " DROP COLUMN " + dialect.quote("seq"));
            LOGGER.info("[CCNR-PM] 已迁移数据库结构 v2 → v3：移除 seq 流水号列（工单身份改为 UUID）");
            return true;
        } catch (SQLException e) {
            LOGGER.error(
                    "[CCNR-PM] 迁移失败（无法移除 pm_tickets.seq）：{}。该列是 NOT NULL 且新代码不再写入，"
                            + "继续使用会导致提交工单失败；请手动执行 ALTER TABLE pm_tickets DROP COLUMN seq 后重启",
                    e.toString());
            return false;
        }
    }

    /** 探测某张表是否存在某一列（一次最小查询，避免依赖 DatabaseMetaData 的方言差异）。 */
    private static boolean hasColumn(Connection conn, String table, String column) {
        try (Statement st = conn.createStatement()) {
            st.executeQuery("SELECT " + column + " FROM " + table + " WHERE 1=0");
            return true;
        } catch (SQLException e) {
            return false;
        }
    }
}
