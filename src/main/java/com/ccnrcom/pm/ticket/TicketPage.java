/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.pm.ticket;

import com.ccnrcom.pm.util.JsonUtil;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 「工单管理面板」的一页数据（纯数据 + JSON 编解码，无 MC 依赖，可直接单测）。
 *
 * <p>为什么用 JSON 字符串下发而不是给每个字段都写 {@code readUtf}/{@code writeUtf}：
 * 这是一页**结构会变**的管理数据（以后要加字段、加筛选、加排序），
 * 逐字段编解码意味着每加一个字段都要动协议两端；而 JSON 只要两端用同一个编解码类就不会错位。
 * 代价是失去编译期字段校验，因此这里配了单测把往返一致性钉死。
 *
 * <p>边界：整页载荷受网络包上限约束，因此**只下发当前页**（默认 10 条），
 * 而不是把全部工单塞进一个包。
 */
public record TicketPage(String filter, int page, int pageSize, int total, List<Entry> tickets) {

    /** 面板列表的一行 + 详情所需字段。 */
    public record Entry(
            String id,
            String status,
            String reporter,
            String targets,
            String category,
            String content,
            String detail,
            long createdAt,
            String handler,
            String note) {

        /** 由工单构造；短号不在这里下发——它由 {@link TicketId} 从 id 现算，避免两处不一致。 */
        public static Entry of(Ticket t) {
            return new Entry(
                    t.id(),
                    t.status().id(),
                    t.reporterName(),
                    t.targetLabel(),
                    t.categoryId(),
                    t.content(),
                    t.detail(),
                    t.createdAt(),
                    t.handlerName() == null ? "" : t.handlerName(),
                    t.handleNote() == null ? "" : t.handleNote());
        }
    }

    public TicketPage {
        filter = filter == null ? "all" : filter;
        tickets = tickets == null ? List.of() : List.copyOf(tickets);
        page = Math.max(1, page);
        pageSize = Math.max(1, pageSize);
        total = Math.max(0, total);
    }

    /** 总页数（至少 1，便于界面显示「第 1/1 页」）。 */
    public int pages() {
        return Math.max(1, (total + pageSize - 1) / pageSize);
    }

    public boolean isEmpty() {
        return tickets.isEmpty();
    }

    public JsonObject toJson() {
        JsonObject root = new JsonObject();
        root.addProperty("filter", filter);
        root.addProperty("page", page);
        root.addProperty("pageSize", pageSize);
        root.addProperty("total", total);
        JsonArray arr = new JsonArray();
        for (Entry e : tickets) {
            JsonObject o = new JsonObject();
            o.addProperty("id", e.id());
            o.addProperty("status", e.status());
            o.addProperty("reporter", e.reporter());
            o.addProperty("targets", e.targets());
            o.addProperty("category", e.category());
            o.addProperty("content", e.content());
            o.addProperty("detail", e.detail());
            o.addProperty("createdAt", e.createdAt());
            o.addProperty("handler", e.handler());
            o.addProperty("note", e.note());
            arr.add(o);
        }
        root.add("tickets", arr);
        return root;
    }

    public String toJsonString() {
        return JsonUtil.GSON.toJson(toJson());
    }

    /**
     * 解析服务端下发的页面。
     *
     * <p>坏输入一律降级成**空页**而不是抛异常：面板打不开比面板空着更糟，
     * 而且空页在界面上会明确显示「没有符合条件的工单」，管理员能看出是数据问题。
     */
    public static Optional<TicketPage> fromJson(String json) {
        if (json == null || json.isBlank()) return Optional.empty();
        try {
            JsonElement el = JsonUtil.GSON.fromJson(json, JsonElement.class);
            if (el == null || !el.isJsonObject()) return Optional.empty();
            JsonObject root = el.getAsJsonObject();
            List<Entry> list = new ArrayList<>();
            if (root.has("tickets") && root.get("tickets").isJsonArray()) {
                for (JsonElement t : root.getAsJsonArray("tickets")) {
                    if (!t.isJsonObject()) continue;
                    JsonObject o = t.getAsJsonObject();
                    String id = JsonUtil.str(o, "id", "");
                    if (id.isBlank()) continue; // 没有身份的条目无从操作，直接跳过
                    list.add(new Entry(
                            id,
                            JsonUtil.str(o, "status", "open"),
                            JsonUtil.str(o, "reporter", ""),
                            JsonUtil.str(o, "targets", ""),
                            JsonUtil.str(o, "category", ""),
                            JsonUtil.str(o, "content", ""),
                            JsonUtil.str(o, "detail", ""),
                            JsonUtil.num(o, "createdAt", 0L),
                            JsonUtil.str(o, "handler", ""),
                            JsonUtil.str(o, "note", "")));
                }
            }
            return Optional.of(new TicketPage(
                    JsonUtil.str(root, "filter", "all"),
                    (int) JsonUtil.num(root, "page", 1L),
                    (int) JsonUtil.num(root, "pageSize", 10L),
                    (int) JsonUtil.num(root, "total", list.size()),
                    list));
        } catch (Exception e) {
            return Optional.empty();
        }
    }
}
