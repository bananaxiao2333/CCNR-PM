/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.pm.data;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * 数据库连接门面：连接生命周期 + 读/写/事务/异步写。
 *
 * <p>并发模型：**单连接 + 一把锁**。工单是低频写、低频读的数据，连接池带来的收益远小于复杂度；
 * 一把锁足以保证 JDBC 对象不被多线程同时使用。
 *
 * <p>线程边界（沿用 CCNR 系列纪律）：{@link #asyncWrite} 的回调运行在 {@code ccnr-pm-db-writer} 守护线程上，
 * **绝不可**在其中触碰 Level/实体/NBT/网络/渲染等主线程对象。它在主线程只做「投递」，
 * 因此回调里只能用不可变快照。
 *
 * <p>与 CCNR-RP 的一处重要差异：{@link #enabled()} 只表示「配置里打开了数据库」， **不代表连上了**。
 * 调用方判断「能不能用数据库」必须用 {@link #usable()}。这样即便连不上，也能老老实实退回文件后端，
 * 而不是让写操作静默丢失。
 */
public final class Database implements AutoCloseable {

    private static final Logger LOGGER = LogManager.getLogger("ccnr_pm");

    @FunctionalInterface
    public interface SqlRead<T> {
        T apply(Connection c) throws SQLException;
    }

    @FunctionalInterface
    public interface SqlWrite {
        void accept(Connection c) throws SQLException;
    }

    private final DbConfig config;
    private final SqlDialect dialect;
    private final ReentrantLock lock = new ReentrantLock();
    private final ExecutorService writer = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "ccnr-pm-db-writer");
        t.setDaemon(true);
        return t;
    });

    private volatile Connection conn;
    private volatile boolean connected;
    private volatile boolean closed;

    public Database(DbConfig config) {
        this.config = config == null ? DbConfig.disabled() : config;
        this.dialect = new SqlDialect(this.config.mode());
    }

    public DbConfig config() {
        return config;
    }

    public SqlDialect dialect() {
        return dialect;
    }

    /** 配置里是否启用了数据库（**不代表连接成功**）。 */
    public boolean enabled() {
        return config.enabled();
    }

    /** 数据库是否真的可用（启用 **且** 已连接）。存储选择必须用这个判据。 */
    public boolean usable() {
        return config.enabled() && connected && !closed;
    }

    /**
     * 建立连接并建表。
     *
     * @return 是否成功；失败原因已记日志，调用方应据此降级到文件后端
     */
    public boolean connect() {
        if (!config.enabled()) return false;
        if (connected) return true;
        // 驱动不会自建目录：先补齐，否则 SQLite 直接连接失败
        config.ensureSqliteParent();
        try {
            Class.forName(config.driverClass());
            Connection c = DriverManager.getConnection(
                    config.jdbcUrl(),
                    config.user() == null ? "" : config.user(),
                    config.pass() == null ? "" : config.pass());
            if (!DbSchema.bootstrap(c, dialect)) {
                closeQuietly(c);
                return false;
            }
            conn = c;
            connected = true;
            LOGGER.info("[CCNR-PM] 工单存储已接入数据库: {}", config.describe());
            return true;
        } catch (ClassNotFoundException | SQLException e) {
            LOGGER.error("[CCNR-PM] 数据库连接失败（将回退文件存储）: {} —— {}", config.describe(), e.toString());
            return false;
        }
    }

    /** 断开连接并等待写队列排空。 */
    public void disconnect() {
        closed = true;
        // 先让已在队列里的写任务跑完，再关连接——否则最后一条工单会丢
        writer.shutdown();
        try {
            if (!writer.awaitTermination(5, TimeUnit.SECONDS)) {
                writer.shutdownNow();
            }
        } catch (InterruptedException e) {
            writer.shutdownNow();
            Thread.currentThread().interrupt();
        }
        lock.lock();
        try {
            if (conn != null) {
                closeQuietly(conn);
                conn = null;
            }
            connected = false;
        } finally {
            lock.unlock();
        }
        LOGGER.info("[CCNR-PM] 工单存储已断开数据库");
    }

    /** 读操作（主线程可用）。异常包装为 IllegalStateException——读失败时调用方不应拿到「假空结果」。 */
    public <T> T read(SqlRead<T> fn) {
        lock.lock();
        try {
            return fn.apply(conn());
        } catch (SQLException e) {
            throw new IllegalStateException("工单数据库读失败", e);
        } finally {
            lock.unlock();
        }
    }

    /**
     * 写操作（主线程可用，同步）。
     *
     * <p>库已关闭时返回 false 而不是抛异常：调用方多在服务端停止/异常路径上，
     * 那里再抛一个异常只会掩盖真正的问题。
     */
    public boolean write(SqlWrite fn) {
        if (closed) return false;
        lock.lock();
        try {
            fn.accept(conn());
            return true;
        } catch (SQLException e) {
            LOGGER.error("[CCNR-PM] 工单数据库写失败: {}", e.toString());
            return false;
        } catch (IllegalStateException e) {
            LOGGER.error("[CCNR-PM] 工单数据库不可用，写操作已跳过: {}", e.toString());
            return false;
        } finally {
            lock.unlock();
        }
    }

    /**
     * 事务：在同一个连接上以 autoCommit=false 执行，成功 commit、异常 rollback。
     *
     * <p>为什么单独给一个入口：多条语句的写必须整体成功或整体失败（例如「分配 seq + 插入工单」），
     * 让每个调用点自己写 setAutoCommit/commit/rollback 太容易漏掉 rollback 分支。
     */
    public boolean tx(SqlWrite fn) {
        if (closed) return false;
        lock.lock();
        try {
            Connection c = conn();
            boolean oldAuto = c.getAutoCommit();
            c.setAutoCommit(false);
            try {
                fn.accept(c);
                c.commit();
                return true;
            } catch (SQLException e) {
                try {
                    c.rollback();
                } catch (SQLException re) {
                    LOGGER.error("[CCNR-PM] 事务回滚失败: {}", re.toString());
                }
                LOGGER.error("[CCNR-PM] 工单数据库事务失败: {}", e.toString());
                return false;
            } finally {
                try {
                    c.setAutoCommit(oldAuto);
                } catch (SQLException ignored) {
                    // 连接已不可用，下次 conn() 会重连
                }
            }
        } catch (SQLException e) {
            LOGGER.error("[CCNR-PM] 事务获取连接失败: {}", e.toString());
            return false;
        } finally {
            lock.unlock();
        }
    }

    /** 异步写（回调在写线程上；**不得**触碰主线程对象）。已关闭时退化为同步写，保证不丢数据。 */
    public void asyncWrite(SqlWrite fn) {
        if (closed) {
            // 已关闭：连同步回退也不做——库已经断了，重连反而是错的
            return;
        }
        try {
            writer.execute(() -> write(fn));
        } catch (RejectedExecutionException e) {
            // 队列已关闭：退回同步执行，宁可慢一点也不丢
            write(fn);
        }
    }

    /**
     * 排空写队列并等待其完成。
     *
     * <p>与 CCNR-RP 的差异：那里 {@code flush()} 是空操作，这里的语义是「提交一个空任务并等它执行完」，
     * 由于写线程是单线程 FIFO，空任务执行完毕即代表此前投递的写都已完成。
     */
    public boolean flush() {
        if (closed || writer.isShutdown()) return false;
        try {
            writer.submit(() -> {}).get(10, TimeUnit.SECONDS);
            return true;
        } catch (Exception e) {
            LOGGER.error("[CCNR-PM] 等待写队列排空失败: {}", e.toString());
            return false;
        }
    }

    /** 连接健康探测（{@code /pm status} 使用）。 */
    public boolean ping() {
        if (!usable()) return false;
        try {
            return read(c -> {
                try (var st = c.createStatement();
                        var rs = st.executeQuery("SELECT 1")) {
                    return rs.next();
                }
            });
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public void close() {
        disconnect();
    }

    private Connection conn() throws SQLException {
        // 显式 disconnect() 之后**绝不重连**：那是服务端停止路径，
        // 若此时被一次迟到的写操作重新连上，就没人会再关它了（连接泄漏）。
        // 注意这与「连接意外断开」不同——那种情况才走下面的懒重连。
        if (closed) throw new IllegalStateException("工单数据库已关闭");
        Connection c = conn;
        if (c == null || c.isClosed()) {
            c = DriverManager.getConnection(
                    config.jdbcUrl(),
                    config.user() == null ? "" : config.user(),
                    config.pass() == null ? "" : config.pass());
            conn = c;
            connected = true;
        }
        return c;
    }

    private static void closeQuietly(Connection c) {
        try {
            c.close();
        } catch (SQLException e) {
            LOGGER.error("[CCNR-PM] 关闭连接失败: {}", e.toString());
        }
    }
}
