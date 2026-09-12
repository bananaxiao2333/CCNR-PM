/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.pm.ticket;

import java.util.Locale;

/**
 * 工单状态。
 *
 * <p>状态集合刻意保持最小：受理人只有「认领 / 办结 / 驳回」三个动作， 多一个中间态就多一条非法迁移路径，而管理面板并不需要更细的粒度。
 *
 * <p>持久化时存 {@link #name()} 的小写形式（{@code open}/{@code claimed}/...）， 这样数据库里的字面量在 SQLite 与 MySQL
 * 上完全一致，也便于人工排查。
 */
public enum TicketStatus {
    /** 待处理：玩家刚提交，尚无受理人。 */
    OPEN,
    /** 处理中：已被某位管理员认领。 */
    CLAIMED,
    /** 已办结：举报成立并已处置。 */
    CLOSED,
    /** 已驳回：举报不成立。 */
    REJECTED;

    /** 解析持久化字面量；未知/空值回退 {@link #OPEN}（旧数据或人工改坏时不让服务停摆）。 */
    public static TicketStatus parse(String raw) {
        if (raw == null) return OPEN;
        String s = raw.trim();
        if (s.isEmpty()) return OPEN;
        try {
            return valueOf(s.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return OPEN;
        }
    }

    /** 持久化字面量（小写）。 */
    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** 是否仍属「未办结」——决定它是否占用受理队列。 */
    public boolean isUnresolved() {
        return this == OPEN || this == CLAIMED;
    }
}
