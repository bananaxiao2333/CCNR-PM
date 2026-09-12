/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.pm.ticket;

import java.util.List;
import java.util.UUID;

/**
 * 一条举报工单（不可变）。
 *
 * <p>工单的身份**就是** {@code id}（UUID），全仓库唯一。早期版本另有一个「流水号」
 * 作为面向人的编号，但它会随存储清空而重复、也可被枚举，因此已移除——
 * 理由与「短号只是显示简写、不是身份」见 {@link TicketId}。
 *
 * @param id 主键（UUID 字符串）——工单的**唯一身份**
 * @param reporterUuid 举报人 UUID
 * @param reporterName 举报人提交时的名字（快照，便于改名后仍可读）
 * @param targets 关联玩家（**可为空列表**——该字段是选填的）
 * @param categoryId 举报类别 id，取自 {@link CategoryRegistry}
 * @param content 举报内容（玩家执行 /a 时那条消息，可在面板内编辑）
 * @param detail 举报详细描述（**选填**，可为空串，可含换行）
 * @param status 当前状态
 * @param createdAt 创建时间（epoch millis）
 * @param handlerName 受理管理员名字，未受理为 null
 * @param handledAt 受理/办结时间（epoch millis），未受理为 0
 * @param handleNote 处理备注，无则 null
 */
public record Ticket(
        String id,
        UUID reporterUuid,
        String reporterName,
        List<TicketTarget> targets,
        String categoryId,
        String content,
        String detail,
        TicketStatus status,
        long createdAt,
        String handlerName,
        long handledAt,
        String handleNote) {

    public Ticket {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("ticket id 不可为空");
        targets = targets == null ? List.of() : List.copyOf(targets);
        status = status == null ? TicketStatus.OPEN : status;
        content = content == null ? "" : content;
        detail = detail == null ? "" : detail;
    }

    /** 新建一条待处理工单（由存储层分配 id）。 */
    public static Ticket create(
            String id,
            UUID reporterUuid,
            String reporterName,
            List<TicketTarget> targets,
            String categoryId,
            String content,
            String detail,
            long createdAt) {
        return new Ticket(
                id,
                reporterUuid,
                reporterName,
                targets,
                categoryId,
                content,
                detail,
                TicketStatus.OPEN,
                createdAt,
                null,
                0L,
                null);
    }

    /** 面向人的短标签，如 {@code #a3f2c1d8}（只是显示简写，不是身份）。 */
    public String label() {
        return TicketId.label(id);
    }

    /** 显示用短号（前 8 位十六进制）。 */
    public String shortId() {
        return TicketId.shortId(id);
    }

    /** 关联玩家的逗号分隔显示串；无关联玩家时返回空串。 */
    public String targetLabel() {
        return TicketTarget.joinNames(targets);
    }

    /** 状态迁移：只改状态与受理字段，其余保持不可变。 */
    public Ticket withStatus(TicketStatus next, String handler, long when, String note) {
        return new Ticket(
                id,
                reporterUuid,
                reporterName,
                targets,
                categoryId,
                content,
                detail,
                next,
                createdAt,
                handler,
                when,
                note);
    }
}
