/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.pm.ticket;

import java.util.Comparator;

/**
 * 管理面板的工单排序（纯逻辑，无 MC 依赖，可直接单测）。
 *
 * <h2>为什么把排序抽出来</h2>
 * 排序规则是「管理员扫一眼就知道先处理哪个」的核心，而它同时又最容易写错
 * （比较器不满足传递性会让列表每次刷新都跳位）。抽成纯函数后可以由测试逐组验证。
 *
 * <h2>顺序（用户指定前两条，其余按可操作性补全）</h2>
 * <ol>
 *   <li><b>我认领的</b>——这是我正在办的事，必须永远在最上面</li>
 *   <li><b>待处理</b>——还没人认领，最需要被看到</li>
 *   <li><b>别人认领的</b>——有人在做，排在我该处理的之后</li>
 *   <li>已办结 / 已驳回（收尾态，排在最后）</li>
 * </ol>
 * 组内顺序：**先按类别**（用户要求「按照类别排序」），同类别再按**时间倒序**（新的在前）。
 *
 * <p>比较器是**全序**（最后用 id 兜底），因此不存在「相等却不稳定」导致的跳位。
 */
public final class TicketSort {

    private TicketSort() {}

    /**
     * 该工单的处置优先级，越小越靠前。
     *
     * @param viewerName 当前查看者的名字（用于识别「我认领的」）
     */
    public static int priority(TicketPage.Entry e, String viewerName) {
        if (e == null) return 9;
        boolean mine = viewerName != null && !viewerName.isBlank() && viewerName.equalsIgnoreCase(e.handler());
        return switch (e.status()) {
            case "claimed" -> mine ? 0 : 2;
            case "open" -> 1;
            case "closed", "rejected" -> 3;
            default -> 4;
        };
    }

    /**
     * 面板列表的比较器。
     *
     * @param viewerName 当前查看者的名字
     */
    public static Comparator<TicketPage.Entry> comparator(String viewerName) {
        return (a, b) -> {
            int p = Integer.compare(priority(a, viewerName), priority(b, viewerName));
            if (p != 0) return p;

            // 组内先按类别（用户要求），空类别排最后
            String ca = a.category() == null ? "" : a.category();
            String cb = b.category() == null ? "" : b.category();
            if (ca.isEmpty() != cb.isEmpty()) return ca.isEmpty() ? 1 : -1;
            int c = ca.compareTo(cb);
            if (c != 0) return c;

            // 同类别按时间倒序：新的在前
            int t = Long.compare(b.createdAt(), a.createdAt());
            if (t != 0) return t;

            // 全序兜底：时间相同（同毫秒提交）时用 id 保证顺序稳定
            String ia = a.id() == null ? "" : a.id();
            String ib = b.id() == null ? "" : b.id();
            return ia.compareTo(ib);
        };
    }
}
