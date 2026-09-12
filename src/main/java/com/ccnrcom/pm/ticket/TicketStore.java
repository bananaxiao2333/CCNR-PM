/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.pm.ticket;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 工单仓储。
 *
 * <p>两个实现：{@link JdbcTicketStore}（SQLite / MySQL）与 {@link JsonTicketStore}（文件回退）。
 * 选择在启动时决定一次，运行期不切换——「中途换后端」会让并发写路径变得难以推理，
 * 而重启换后端只需要改一行配置。
 *
 * <p>工单标识是 UUID（见 {@link TicketId}）：存储负责生成，调用方只能用
 * {@link NewTicket} 描述「想建一条什么样的工单」，无法自带 id——身份不该由调用方发明。
 */
public interface TicketStore extends AutoCloseable {

    /** 后端短名（{@code sqlite} / {@code mysql} / {@code json}），用于 {@code /pm status} 与日志。 */
    String backend();

    /** 初始化（建表 / 载入文件）。返回是否可用；false 时调用方应更换后端。 */
    boolean init();

    /**
     * 落库一条新工单。
     *
     * <p>id 由本方法分配（UUID）。早期版本在这里做「事务内取 max(seq)+1」，改成 UUID 后
     * 那次事务与唯一序号索引都不需要了——少一处并发争用，也少一处可能重复的来源。
     *
     * @return 落库后的完整工单；失败返回空
     */
    Optional<Ticket> insert(NewTicket ticket);

    Optional<Ticket> byId(String id);

    /**
     * 按标识前缀查找（玩家在聊天里只会口述短号，见 {@link TicketId}）。
     *
     * <p>返回**列表**而不是单条：前缀可能命中多张工单，此时上层必须报「请提供更多字符」，
     * 而不是替用户挑一张——猜一个等于随机操作某张工单。
     *
     * @param limit 最多返回几条（用于判定「唯一」时取 2 就够）
     */
    List<Ticket> byIdPrefix(String prefix, int limit);

    /**
     * 列出**活跃**（未办结）工单，按创建时间倒序，最多 {@code limit} 条。
     *
     * <p>看板专用：活跃的定义是「open 或 claimed」，这不是单个 {@link TicketStatus} 能表达的，
     * 所以单独给一个方法而不是靠上层过滤——数据库侧可以用 {@code status IN (...)} 走索引，
     * 而无需把全部历史工单读进内存再筛。
     */
    List<Ticket> listActive(int limit);

    /** 按创建时间倒序列出；{@code filter} 为 null 时不过滤状态。 */
    List<Ticket> list(TicketStatus filter, int limit, int offset);

    /** 计数；{@code filter} 为 null 时统计全部。 */
    int count(TicketStatus filter);

    /** 某举报人尚未办结的工单数（限流用）。 */
    int countUnresolvedByReporter(UUID reporterUuid);

    /** 某举报人最近提交时间（epoch millis），无记录返回 0（冷却用）。 */
    long lastSubmitAt(UUID reporterUuid);

    /**
     * 某管理员忽略掉的工单 id 集合。
     *
     * <p>忽略是**按管理员隔离**的：A 忽略不等于 B 也忽略。因此它属于「谁看什么」而不是工单本身，
     * 单独一张表存，不去污染工单记录。
     */
    java.util.Set<String> ignoredIds(UUID adminUuid);

    /** 设置/取消忽略。返回是否真的改变了状态。 */
    boolean setIgnored(UUID adminUuid, String ticketId, boolean ignored);

    /** 状态迁移；按 id 定位。返回是否真正更新了一行。 */
    boolean updateStatus(String id, TicketStatus status, String handler, long when, String note);

    /** 释放资源（关连接 / 排空写队列）。 */
    @Override
    void close();

    /**
     * 新建工单的输入（不含 id / status / 时间——这些由存储层补齐）。
     *
     * <p>刻意与 {@link Ticket} 分开：避免调用方传一个自己编的 id 进来，
     * 让「身份只能由存储分配」这件事在类型上就无法绕过。
     */
    record NewTicket(
            UUID reporterUuid,
            String reporterName,
            List<TicketTarget> targets,
            String categoryId,
            String content,
            String detail,
            long createdAt) {}
}
