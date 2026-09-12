/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.pm.data;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * 工单存储后端配置（{@code config/ccnr_pm/db.properties}）。
 *
 * <p>设计取舍：**默认关闭**。数据库是可选增强，未配置时工单落在 {@code world/ccnr_pm/tickets.json}
 * （原子写 + .bak），单人服/中小服不需要任何额外部署即可使用。要 SQL 查询与备份才需要打开。
 *
 * @param enabled 是否启用数据库（false 时一律走 JSON 文件）
 * @param mode 后端类型
 * @param sqliteFile SQLite 文件路径（相对路径按游戏根目录解析）
 * @param host MySQL 主机
 * @param port MySQL 端口
 * @param database MySQL 库名
 * @param user MySQL 用户
 * @param pass MySQL 密码（为空时回退环境变量 {@code CCNR_PM_DB_PASS}）
 */
public record DbConfig(
        boolean enabled,
        DbType mode,
        String sqliteFile,
        String host,
        int port,
        String database,
        String user,
        String pass) {

    private static final Logger LOGGER = LogManager.getLogger("ccnr_pm");

    /** 默认（未配置）状态：走文件后端。 */
    public static DbConfig disabled() {
        return new DbConfig(
                false, DbType.SQLITE, "config/ccnr_pm/ccnr-pm.db", "localhost", 3306, "ccnr_pm", "ccnr", "");
    }

    /**
     * 读取配置文件。
     *
     * <p>任一环节出问题（文件缺失、不可读、{@code db.mode} 无法识别）都退化为 {@link #disabled()} 而不是抛异常：
     * 存储后端配置错误不应该让服务端起不来——工单功能降级为文件存储仍然完全可用。
     */
    public static DbConfig load(Path file) {
        if (file == null || !Files.isRegularFile(file)) return disabled();
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            p.load(in);
        } catch (IOException e) {
            LOGGER.error("[CCNR-PM] 读取 db.properties 失败: {} —— {}", file, e.toString());
            return disabled();
        }
        DbConfig def = disabled();
        boolean enabled =
                Boolean.parseBoolean(p.getProperty("db.enabled", "false").trim());
        DbType mode = DbType.parse(p.getProperty("db.mode", "sqlite"));
        if (mode == null) {
            LOGGER.error("[CCNR-PM] db.mode 无法识别（应为 sqlite 或 mysql），已停用数据库后端");
            return disabled();
        }
        String pass = p.getProperty("db.pass", "").trim();
        if (pass.isEmpty()) {
            String env = System.getenv("CCNR_PM_DB_PASS");
            if (env != null) pass = env;
        }
        int port;
        try {
            port = Integer.parseInt(p.getProperty("db.port", "3306").trim());
        } catch (NumberFormatException e) {
            LOGGER.error("[CCNR-PM] db.port 不是数字，回退 3306");
            port = 3306;
        }
        return new DbConfig(
                enabled,
                mode,
                p.getProperty("db.file", def.sqliteFile()).trim(),
                p.getProperty("db.host", def.host()).trim(),
                port,
                p.getProperty("db.database", def.database()).trim(),
                p.getProperty("db.user", def.user()).trim(),
                pass);
    }

    /** JDBC URL。 */
    public String jdbcUrl() {
        if (mode == DbType.MYSQL) {
            return "jdbc:mysql://" + host + ":" + port + "/" + database
                    + "?useSSL=false&serverTimezone=UTC&allowPublicKeyRetrieval=true&characterEncoding=utf8";
        }
        return "jdbc:sqlite:" + sqliteFile;
    }

    public String driverClass() {
        return mode == DbType.MYSQL ? "com.mysql.cj.jdbc.Driver" : "org.sqlite.JDBC";
    }

    /** 日志/命令输出用的脱敏描述。 */
    public String describe() {
        return mode == DbType.MYSQL
                ? "mysql://" + host + ":" + port + "/" + database + " user=" + user
                : "sqlite:" + sqliteFile;
    }

    /** 生成示例配置文件内容（{@code /pm db init} 使用）。 */
    public static String template() {
        return """
                # CCNR-PM 工单存储后端配置
                # 关闭时工单写入 world/ccnr_pm/tickets.json（原子写 + .bak 备份）
                db.enabled=false
                # sqlite 或 mysql
                db.mode=sqlite
                db.file=config/ccnr_pm/ccnr-pm.db
                db.host=localhost
                db.port=3306
                db.database=ccnr_pm
                db.user=ccnr
                # 留空时回退环境变量 CCNR_PM_DB_PASS
                db.pass=
                """;
    }

    /** 确保 SQLite 目标目录存在；驱动不会自建目录，缺目录会直接连接失败。 */
    public void ensureSqliteParent() {
        if (mode != DbType.SQLITE || sqliteFile == null || sqliteFile.isBlank()) return;
        try {
            Path p = Path.of(sqliteFile);
            Path parent = p.toAbsolutePath().getParent();
            if (parent != null) Files.createDirectories(parent);
        } catch (Exception e) {
            LOGGER.error("[CCNR-PM] 创建 SQLite 目录失败: {} —— {}", sqliteFile, e.toString());
        }
    }

    /** 写出一份示例配置（仅在文件不存在时）。 */
    public static boolean writeTemplate(Path file) {
        try {
            if (Files.exists(file)) return false;
            Path parent = file.toAbsolutePath().getParent();
            if (parent != null) Files.createDirectories(parent);
            Files.writeString(file, template(), StandardCharsets.UTF_8);
            return true;
        } catch (IOException e) {
            LOGGER.error("[CCNR-PM] 写出 db.properties 模板失败: {} —— {}", file, e.toString());
            return false;
        }
    }
}
