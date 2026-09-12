/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.pm.ticket;

import com.ccnrcom.pm.data.Database;
import com.ccnrcom.pm.data.DbSchema;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * 数据库工单仓储（SQLite / MySQL）。
 *
 * <p>所有 SQL 都以显式字符串书写（本仓库的既定风格），标识符一律经 {@link com.ccnrcom.pm.data.SqlDialect#quote}
 * 处理，不拼接任何用户输入——玩家填写的举报内容只以绑定参数进入语句。
 *
 * <p>工单身份是 UUID（见 {@link TicketId}），由 {@link #insert} 分配：
 * 一条 INSERT 即可，不需要「事务内取 max+1」这类计数逻辑。
 */
public final class JdbcTicketStore implements TicketStore {

    private static final Logger LOGGER = LogManager.getLogger("ccnr_pm");

    private final Database db;
    private final String selectCols;

    public JdbcTicketStore(Database db) {
        this.db = db;
        StringBuilder sb = new StringBuilder();
        for (String c : DbSchema.TICKET_COLUMNS) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(db.dialect().quote(c));
        }
        this.selectCols = sb.toString();
    }

    @Override
    public String backend() {
        return db.config().mode().name().toLowerCase(Locale.ROOT);
    }

    @Override
    public boolean init() {
        // 建表在 Database.connect() 里完成；这里只确认真的连上了
        return db.usable();
    }

    @Override
    public Optional<Ticket> insert(NewTicket n) {
        if (n == null) return Optional.empty();
        String id = TicketId.newId();
        String table = db.dialect().quote(DbSchema.TABLE_TICKETS);
        String insertSql = "INSERT INTO " + table + " (" + selectCols + ") VALUES ("
                + db.dialect().placeholders(DbSchema.TICKET_COLUMNS.size()) + ")";

        // 身份是 UUID：以前这里要「事务内取 max(seq)+1 再插入」，现在一条 INSERT 就够，
        // 既不争用也不需要唯一序号索引兜底
        boolean ok = db.write(c -> {
            try (PreparedStatement ps = c.prepareStatement(insertSql)) {
                int i = 1;
                ps.setString(i++, id);
                setString(ps, i++, uuidStr(n.reporterUuid()));
                setString(ps, i++, n.reporterName());
                // 关联玩家多值：整列存 JSON 数组（见 TicketTarget.listToJson）
                setString(ps, i++, TicketTarget.listToJson(n.targets()));
                setString(ps, i++, n.categoryId());
                setString(ps, i++, n.content());
                setString(ps, i++, n.detail());
                setString(ps, i++, TicketStatus.OPEN.id());
                ps.setLong(i++, n.createdAt());
                setString(ps, i++, null);
                ps.setLong(i++, 0L);
                setString(ps, i, null);
                ps.executeUpdate();
            }
        });
        if (!ok) {
            LOGGER.error("[CCNR-PM] 工单写入失败（表 {}）", DbSchema.TABLE_TICKETS);
            return Optional.empty();
        }
        return Optional.of(Ticket.create(
                id,
                n.reporterUuid(),
                n.reporterName(),
                n.targets(),
                n.categoryId(),
                n.content(),
                n.detail(),
                n.createdAt()));
    }

    @Override
    public Optional<Ticket> byId(String id) {
        if (id == null || id.isBlank()) return Optional.empty();
        // 规范化后再比较：id 以连字符形式存储，而玩家可能粘贴 32 位无连字符形式。
        // REPLACE 在 SQLite 与 MySQL 上都可用；代价是这条查询用不上主键索引——
        // 工单量级（百千条）下可忽略，换来的是「怎么粘都能查到」。
        String sql = "SELECT " + selectCols + " FROM " + db.dialect().quote(DbSchema.TABLE_TICKETS) + " WHERE REPLACE("
                + db.dialect().quote("id") + ",'-','')=?";
        try {
            return db.read(c -> {
                try (PreparedStatement ps = c.prepareStatement(sql)) {
                    ps.setString(1, TicketId.normalize(id));
                    try (ResultSet rs = ps.executeQuery()) {
                        return rs.next() ? Optional.of(map(rs)) : Optional.<Ticket>empty();
                    }
                }
            });
        } catch (Exception e) {
            LOGGER.error("[CCNR-PM] 读取工单 {} 失败: {}", id, e.toString());
            return Optional.empty();
        }
    }

    @Override
    public List<Ticket> byIdPrefix(String prefix, int limit) {
        if (prefix == null || prefix.isBlank()) return List.of();
        // 同样先去掉存储侧的连字符再前缀匹配：否则前缀一旦跨过第 8 位后的连字符就会失配
        // （例如用户输入 3f2a91c4-5b7e 归一化为 3f2a91c45b7e，而库里存的是 3f2a91c4-5b7e-…）
        String sql = "SELECT " + selectCols + " FROM " + db.dialect().quote(DbSchema.TABLE_TICKETS) + " WHERE REPLACE("
                + db.dialect().quote("id") + ",'-','') LIKE ? LIMIT ?";
        try {
            return db.read(c -> {
                try (PreparedStatement ps = c.prepareStatement(sql)) {
                    ps.setString(1, TicketId.normalize(prefix) + "%");
                    ps.setInt(2, Math.max(1, limit));
                    try (ResultSet rs = ps.executeQuery()) {
                        List<Ticket> out = new java.util.ArrayList<>();
                        while (rs.next()) out.add(map(rs));
                        return List.copyOf(out);
                    }
                }
            });
        } catch (Exception e) {
            LOGGER.error("[CCNR-PM] 按前缀查询工单失败: {}", e.toString());
            return List.of();
        }
    }

    @Override
    public List<Ticket> list(TicketStatus filter, int limit, int offset) {
        String q = db.dialect().quote(DbSchema.TABLE_TICKETS);
        String statusCol = db.dialect().quote("status");
        StringBuilder sql =
                new StringBuilder("SELECT ").append(selectCols).append(" FROM ").append(q);
        if (filter != null) sql.append(" WHERE ").append(statusCol).append("=?");
        sql.append(" ORDER BY ")
                .append(db.dialect().quote("created_at"))
                .append(" DESC, ")
                .append(db.dialect().quote("id"))
                .append(" DESC LIMIT ? OFFSET ?");
        try {
            return db.read(c -> {
                try (PreparedStatement ps = c.prepareStatement(sql.toString())) {
                    int i = 1;
                    if (filter != null) ps.setString(i++, filter.id());
                    ps.setInt(i++, Math.max(1, limit));
                    ps.setInt(i, Math.max(0, offset));
                    try (ResultSet rs = ps.executeQuery()) {
                        List<Ticket> out = new ArrayList<>();
                        while (rs.next()) out.add(map(rs));
                        return List.copyOf(out);
                    }
                }
            });
        } catch (Exception e) {
            LOGGER.error("[CCNR-PM] 列出工单失败: {}", e.toString());
            return List.of();
        }
    }

    @Override
    public List<Ticket> listActive(int limit) {
        String q = db.dialect().quote(DbSchema.TABLE_TICKETS);
        String sql =
                "SELECT " + selectCols + " FROM " + q + " WHERE " + db.dialect().quote("status") + " IN (?, ?)"
                        + " ORDER BY " + db.dialect().quote("created_at") + " DESC, "
                        + db.dialect().quote("id")
                        + " DESC LIMIT ?";
        try {
            return db.read(c -> {
                try (PreparedStatement ps = c.prepareStatement(sql)) {
                    ps.setString(1, TicketStatus.OPEN.id());
                    ps.setString(2, TicketStatus.CLAIMED.id());
                    ps.setInt(3, Math.max(1, limit));
                    try (ResultSet rs = ps.executeQuery()) {
                        List<Ticket> out = new java.util.ArrayList<>();
                        while (rs.next()) out.add(map(rs));
                        return List.copyOf(out);
                    }
                }
            });
        } catch (Exception e) {
            LOGGER.error("[CCNR-PM] 列出活跃工单失败: {}", e.toString());
            return List.of();
        }
    }

    @Override
    public int count(TicketStatus filter) {
        String q = db.dialect().quote(DbSchema.TABLE_TICKETS);
        String sql = "SELECT COUNT(*) FROM " + q
                + (filter == null ? "" : " WHERE " + db.dialect().quote("status") + "=?");
        return countQuery(sql, filter == null ? null : filter.id());
    }

    @Override
    public int countUnresolvedByReporter(UUID reporterUuid) {
        if (reporterUuid == null) return 0;
        String q = db.dialect().quote(DbSchema.TABLE_TICKETS);
        String sql = "SELECT COUNT(*) FROM " + q + " WHERE " + db.dialect().quote("reporter_uuid") + "=? AND "
                + db.dialect().quote("status") + " IN (?, ?)";
        try {
            return db.read(c -> {
                try (PreparedStatement ps = c.prepareStatement(sql)) {
                    ps.setString(1, reporterUuid.toString());
                    ps.setString(2, TicketStatus.OPEN.id());
                    ps.setString(3, TicketStatus.CLAIMED.id());
                    try (ResultSet rs = ps.executeQuery()) {
                        return rs.next() ? rs.getInt(1) : 0;
                    }
                }
            });
        } catch (Exception e) {
            LOGGER.error("[CCNR-PM] 统计未办结工单失败: {}", e.toString());
            return 0;
        }
    }

    @Override
    public long lastSubmitAt(UUID reporterUuid) {
        if (reporterUuid == null) return 0L;
        String q = db.dialect().quote(DbSchema.TABLE_TICKETS);
        String sql = "SELECT COALESCE(MAX(" + db.dialect().quote("created_at") + "), 0) FROM " + q + " WHERE "
                + db.dialect().quote("reporter_uuid") + "=?";
        try {
            return db.read(c -> {
                try (PreparedStatement ps = c.prepareStatement(sql)) {
                    ps.setString(1, reporterUuid.toString());
                    try (ResultSet rs = ps.executeQuery()) {
                        return rs.next() ? rs.getLong(1) : 0L;
                    }
                }
            });
        } catch (Exception e) {
            LOGGER.error("[CCNR-PM] 读取最近提交时间失败: {}", e.toString());
            return 0L;
        }
    }

    @Override
    public boolean updateStatus(String id, TicketStatus status, String handler, long when, String note) {
        if (id == null || id.isBlank()) return false;
        String sql = "UPDATE " + db.dialect().quote(DbSchema.TABLE_TICKETS) + " SET "
                + db.dialect().quote("status") + "=?, "
                + db.dialect().quote("handler") + "=?, "
                + db.dialect().quote("handled_at") + "=?, "
                + db.dialect().quote("handle_note") + "=? WHERE REPLACE("
                + db.dialect().quote("id") + ",'-','')=?";
        // 必须看影响行数：只写 UPDATE 不看返回值的话，对一个不存在的 id 也会「成功」，
        // 于是命令层会告诉管理员「已办结」，而实际什么都没发生
        int[] affected = new int[1];
        boolean ok = db.write(c -> {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setString(1, status.id());
                setString(ps, 2, handler);
                ps.setLong(3, when);
                setString(ps, 4, note);
                ps.setString(5, TicketId.normalize(id));
                affected[0] = ps.executeUpdate();
            }
        });
        return ok && affected[0] > 0;
    }

    @Override
    public java.util.Set<String> ignoredIds(UUID adminUuid) {
        if (adminUuid == null) return java.util.Set.of();
        String sql = "SELECT " + db.dialect().quote("ticket_id") + " FROM "
                + db.dialect().quote(DbSchema.TABLE_IGNORES) + " WHERE "
                + db.dialect().quote("admin_uuid") + "=?";
        try {
            return db.read(c -> {
                try (PreparedStatement ps = c.prepareStatement(sql)) {
                    ps.setString(1, adminUuid.toString());
                    try (ResultSet rs = ps.executeQuery()) {
                        java.util.Set<String> out = new java.util.LinkedHashSet<>();
                        while (rs.next()) out.add(rs.getString(1));
                        return java.util.Set.copyOf(out);
                    }
                }
            });
        } catch (Exception e) {
            LOGGER.error("[CCNR-PM] 读取忽略名单失败: {}", e.toString());
            return java.util.Set.of();
        }
    }

    @Override
    public boolean setIgnored(UUID adminUuid, String ticketId, boolean ignored) {
        if (adminUuid == null || ticketId == null || ticketId.isBlank()) return false;
        if (ignored) {
            // 忽略名单按 (admin, ticket) 主键去重，重复忽略不会报错也不会产生第二行
            String sql = db.dialect()
                    .upsert(
                            DbSchema.TABLE_IGNORES,
                            List.of("admin_uuid", "ticket_id", "created_at"),
                            List.of("admin_uuid", "ticket_id"));
            boolean ok = db.write(c -> {
                try (PreparedStatement ps = c.prepareStatement(sql)) {
                    ps.setString(1, adminUuid.toString());
                    ps.setString(2, ticketId);
                    ps.setLong(3, System.currentTimeMillis());
                    ps.executeUpdate();
                }
            });
            return ok;
        }
        String sql = "DELETE FROM " + db.dialect().quote(DbSchema.TABLE_IGNORES) + " WHERE "
                + db.dialect().quote("admin_uuid") + "=? AND " + db.dialect().quote("ticket_id") + "=?";
        int[] affected = new int[1];
        boolean ok = db.write(c -> {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setString(1, adminUuid.toString());
                ps.setString(2, ticketId);
                affected[0] = ps.executeUpdate();
            }
        });
        return ok && affected[0] > 0;
    }

    @Override
    public void close() {
        // 连接由 Database 统一持有与关闭
    }

    private int countQuery(String sql, String arg) {
        try {
            return db.read(c -> {
                try (PreparedStatement ps = c.prepareStatement(sql)) {
                    if (arg != null) ps.setString(1, arg);
                    try (ResultSet rs = ps.executeQuery()) {
                        return rs.next() ? rs.getInt(1) : 0;
                    }
                }
            });
        } catch (Exception e) {
            LOGGER.error("[CCNR-PM] 统计工单失败: {}", e.toString());
            return 0;
        }
    }

    private Ticket map(ResultSet rs) throws SQLException {
        return new Ticket(
                rs.getString("id"),
                uuid(rs.getString("reporter_uuid")),
                rs.getString("reporter_name"),
                TicketTarget.listFromJson(rs.getString("targets")),
                rs.getString("category"),
                rs.getString("content"),
                rs.getString("detail"),
                TicketStatus.parse(rs.getString("status")),
                rs.getLong("created_at"),
                rs.getString("handler"),
                rs.getLong("handled_at"),
                rs.getString("handle_note"));
    }

    private static void setString(PreparedStatement ps, int i, String v) throws SQLException {
        if (v == null) {
            ps.setNull(i, Types.VARCHAR);
        } else {
            ps.setString(i, v);
        }
    }

    private static String uuidStr(UUID u) {
        return u == null ? null : u.toString();
    }

    private static UUID uuid(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
