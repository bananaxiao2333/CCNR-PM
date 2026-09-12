/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.pm.data;

import java.util.List;
import java.util.StringJoiner;

/**
 * SQL 方言差异（只覆盖本模组真正用到的那几处）。
 *
 * <p>为什么不做成子类：差异面很窄（标识符引号、占位符、upsert 冲突子句），
 * 用分支比多态更好读，也避免出现「方言类里塞业务 SQL」的滑坡。
 *
 * <p>SQLite 与 MySQL 都用反引号引用标识符，因此 {@link #quote} 无需分支； 占位符两者都是 {@code ?}，无需分支。
 */
public final class SqlDialect {

    private final DbType type;

    public SqlDialect(DbType type) {
        this.type = type == null ? DbType.SQLITE : type;
    }

    public DbType type() {
        return type;
    }

    /** 引用标识符（两者都支持反引号）。 */
    public String quote(String ident) {
        return "`" + ident + "`";
    }

    /** 生成 {@code ?, ?, ?}。 */
    public String placeholders(int n) {
        if (n <= 0) return "";
        StringJoiner sj = new StringJoiner(", ");
        for (int i = 0; i < n; i++) sj.add("?");
        return sj.toString();
    }

    /**
     * 删除索引的语句。
     *
     * <p>两端语法不同：SQLite 是 {@code DROP INDEX [IF EXISTS] name}，
     * MySQL 必须写成 {@code DROP INDEX name ON table} 且**不支持 IF EXISTS**。
     * 因此 MySQL 分支调用方需要容忍「索引不存在」的异常——迁移阶梯里已如此处理。
     */
    public String dropIndex(String index, String table) {
        if (type == DbType.MYSQL) {
            return "DROP INDEX " + quote(index) + " ON " + quote(table);
        }
        return "DROP INDEX IF EXISTS " + quote(index);
    }

    /**
     * upsert 语句。
     *
     * <p>**调用前提**：{@code columns} 中必须至少有一个非主键列。若全部列都是主键，冲突时的 SET 子句为空，
     * 会生成语法非法的 SQL。本模组没有纯主键表，因此这里直接抛异常把误用暴露在开发期，而不是留到运行期。
     *
     * @param table 表名
     * @param columns 全部列（顺序即 VALUES 顺序）
     * @param pkColumns 主键列（冲突判定依据）
     */
    public String upsert(String table, List<String> columns, List<String> pkColumns) {
        List<String> nonPk =
                columns.stream().filter(c -> !pkColumns.contains(c)).toList();
        if (nonPk.isEmpty()) {
            throw new IllegalArgumentException("upsert 需要至少一个非主键列（表 " + table + "）");
        }
        StringJoiner cols = new StringJoiner(", ");
        for (String c : columns) cols.add(quote(c));

        StringBuilder sb = new StringBuilder();
        sb.append("INSERT INTO ")
                .append(quote(table))
                .append(" (")
                .append(cols)
                .append(") VALUES (")
                .append(placeholders(columns.size()))
                .append(") ");

        if (type == DbType.MYSQL) {
            sb.append("ON DUPLICATE KEY UPDATE ");
            StringJoiner sets = new StringJoiner(", ");
            for (String c : nonPk) {
                sets.add(quote(c) + "=VALUES(" + quote(c) + ")");
            }
            sb.append(sets);
        } else {
            StringJoiner pk = new StringJoiner(", ");
            for (String c : pkColumns) pk.add(quote(c));
            StringJoiner sets = new StringJoiner(", ");
            for (String c : nonPk) {
                sets.add(quote(c) + "=excluded." + quote(c));
            }
            sb.append("ON CONFLICT(").append(pk).append(") DO UPDATE SET ").append(sets);
        }
        return sb.toString();
    }
}
