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
import java.util.Map;
import java.util.UUID;

/**
 * 左上角常驻看板的数据（纯数据 + JSON 编解码，无 MC 依赖，可直接单测）。
 *
 * <h2>为什么与 {@link TicketPage} 分开</h2>
 * 面板那一页要显示受理人、处理备注等**管理字段**（管理员要据此判断流程状态）。
 * 而看板卡片画的是「类别 + 举报内容 + 一排头像」——管理员扫一眼要知道**是什么事、谁报的**，
 * 所以内容必须有；但受理人、备注、详细描述这些在卡片上一个字都不显示，传了纯属浪费。
 *
 * <p>因此这里只带看板真正要用的字段，并**自带头像表**：卡片要在离线服务器上显示真实头像，
 * 而客户端按离线 UUID 查不到皮肤，所以头像必须随数据一起下发（服务端已抓好的 base64）。
 *
 * <h2>为什么是「整份替换」而不是增量</h2>
 * 服务端每次推一份完整的活跃列表，客户端整体替换。
 * 增量意味着客户端要维护合并逻辑，而「哪张工单还算活跃」这件事只有服务端知道
 * （被人认领、办结、驳回都会让它离开看板）。整份替换不会出现"客户端以为还在、服务端早就不算了"。
 */
public record TicketBoard(List<Card> cards) {

    /**
     * 看板上一张卡片需要的全部信息。
     *
     * @param content 举报内容 → 卡片**左上角标题**（玩家真正想说的那句话）
     * @param category 类别 id → 卡片**右上角**的类别标签
     * @param detail 详细描述 → 卡片**标题下方**的补充说明
     */
    public record Card(
            String id,
            String status,
            String category,
            String content,
            String detail,
            String reporter,
            String reporterUuid,
            List<TargetRef> targets,
            Map<String, String> avatars) {

        public Card {
            status = status == null ? "open" : status;
            category = category == null ? "" : category;
            content = content == null ? "" : content;
            detail = detail == null ? "" : detail;
            reporter = reporter == null ? "" : reporter;
            reporterUuid = reporterUuid == null ? "" : reporterUuid;
            targets = targets == null ? List.of() : List.copyOf(targets);
            avatars = avatars == null ? Map.of() : Map.copyOf(avatars);
        }

        /** 面向人的短标签（由 id 现算，避免两处算法不一致）。 */
        public String label() {
            return TicketId.label(id);
        }

        public UUID reporterUuidOrNull() {
            return parseUuid(reporterUuid);
        }
    }

    /** 关联玩家的最小引用（名字 + 可选 UUID）。 */
    public record TargetRef(String uuid, String name) {
        public TargetRef {
            uuid = uuid == null ? "" : uuid;
            name = name == null ? "" : name;
        }

        public UUID uuidOrNull() {
            return parseUuid(uuid);
        }

        public static TargetRef of(TicketTarget t) {
            return new TargetRef(t.uuid() == null ? "" : t.uuid().toString(), t.name());
        }
    }

    /** 看板卡片正文（标题）的最大字符数。超过的部分由管理面板显示完整内容。 */
    public static final int BOARD_CONTENT_LIMIT = 240;

    /** 看板卡片详细描述的最大字符数。卡片只显示一两行，截断在服务端做，客户端只管排版。 */
    public static final int BOARD_DETAIL_LIMIT = 200;

    public TicketBoard {
        cards = cards == null ? List.of() : List.copyOf(cards);
    }

    /**
     * 截断卡片正文。
     *
     * <p>**在服务端截断而不是让客户端裁**：看板一次要下发十几张卡片，
     * 每张都拖上完整正文（配置允许到 4096 字）会让载荷凭空大一个数量级，
     * 而卡片只能显示两行。截断长度由服务端决定，客户端就只管排版。
     */
    private static String clipContent(String content) {
        return clip(content, BOARD_CONTENT_LIMIT);
    }

    private static String clipDetail(String detail) {
        return clip(detail, BOARD_DETAIL_LIMIT);
    }

    private static String clip(String text, int limit) {
        if (text == null) return "";
        String t = text.strip();
        return t.length() <= limit ? t : t.substring(0, limit);
    }

    public static TicketBoard empty() {
        return new TicketBoard(List.of());
    }

    public boolean isEmpty() {
        return cards.isEmpty();
    }

    public int size() {
        return cards.size();
    }

    /** 由工单构造卡片；{@code avatars} 是服务端已缓存的 base64 头像表。 */
    public static Card cardOf(Ticket t, Map<String, String> avatars) {
        List<TargetRef> refs = new ArrayList<>(t.targets().size());
        for (TicketTarget target : t.targets()) refs.add(TargetRef.of(target));
        return new Card(
                t.id(),
                t.status().id(),
                t.categoryId(),
                // 卡片只显示前 BOARD_CONTENT_LIMIT 个字符：一张 16:9 小卡片放不下更多，
                // 完整内容在管理面板里读
                clipContent(t.content()),
                clipDetail(t.detail()),
                t.reporterName(),
                t.reporterUuid() == null ? "" : t.reporterUuid().toString(),
                refs,
                avatars);
    }

    // ------------------------------------------------------------------
    // JSON
    // ------------------------------------------------------------------

    public JsonObject toJson() {
        JsonObject root = new JsonObject();
        JsonArray arr = new JsonArray();
        for (Card c : cards) {
            JsonObject o = new JsonObject();
            o.addProperty("id", c.id());
            o.addProperty("status", c.status());
            o.addProperty("category", c.category());
            o.addProperty("content", c.content());
            o.addProperty("detail", c.detail());
            o.addProperty("reporter", c.reporter());
            o.addProperty("reporterUuid", c.reporterUuid());
            JsonArray ts = new JsonArray();
            for (TargetRef t : c.targets()) {
                JsonObject to = new JsonObject();
                to.addProperty("uuid", t.uuid());
                to.addProperty("name", t.name());
                ts.add(to);
            }
            o.add("targets", ts);
            JsonObject av = new JsonObject();
            for (Map.Entry<String, String> e : c.avatars().entrySet()) {
                av.addProperty(e.getKey(), e.getValue());
            }
            o.add("avatars", av);
            arr.add(o);
        }
        root.add("cards", arr);
        return root;
    }

    public String toJsonString() {
        return JsonUtil.GSON.toJson(toJson());
    }

    /**
     * 解析服务端下发的看板。
     *
     * <p>坏输入降级为**空看板**而不是抛异常：看板是常驻浮层，让它把游戏搞崩比让它空着糟得多。
     * 缺 id 的条目直接跳过——没有身份的卡片无从操作，画出来只会让人点它。
     */
    public static TicketBoard fromJson(String json) {
        if (json == null || json.isBlank()) return empty();
        try {
            JsonElement el = JsonUtil.GSON.fromJson(json, JsonElement.class);
            if (el == null || !el.isJsonObject()) return empty();
            JsonObject root = el.getAsJsonObject();
            if (!root.has("cards") || !root.getAsJsonArray("cards").isJsonArray()) return empty();

            List<Card> cards = new ArrayList<>();
            for (JsonElement e : root.getAsJsonArray("cards")) {
                if (!e.isJsonObject()) continue;
                JsonObject o = e.getAsJsonObject();
                String id = JsonUtil.str(o, "id", "");
                if (id.isBlank()) continue;

                List<TargetRef> targets = new ArrayList<>();
                if (o.has("targets") && o.get("targets").isJsonArray()) {
                    for (JsonElement te : o.getAsJsonArray("targets")) {
                        if (!te.isJsonObject()) continue;
                        JsonObject to = te.getAsJsonObject();
                        String name = JsonUtil.str(to, "name", "");
                        if (name.isBlank()) continue;
                        targets.add(new TargetRef(JsonUtil.str(to, "uuid", ""), name));
                    }
                }

                Map<String, String> avatars = new java.util.HashMap<>();
                if (o.has("avatars") && o.get("avatars").isJsonObject()) {
                    for (Map.Entry<String, JsonElement> ae :
                            o.getAsJsonObject("avatars").entrySet()) {
                        try {
                            if (ae.getValue().isJsonPrimitive()) {
                                avatars.put(ae.getKey(), ae.getValue().getAsString());
                            }
                        } catch (Exception ignored) {
                            // 单个头像坏掉只丢这个头像，不影响整张卡片
                        }
                    }
                }

                cards.add(new Card(
                        id,
                        JsonUtil.str(o, "status", "open"),
                        JsonUtil.str(o, "category", ""),
                        JsonUtil.str(o, "content", ""),
                        JsonUtil.str(o, "detail", ""),
                        JsonUtil.str(o, "reporter", ""),
                        JsonUtil.str(o, "reporterUuid", ""),
                        targets,
                        avatars));
            }
            return new TicketBoard(cards);
        } catch (Exception e) {
            return empty();
        }
    }

    private static UUID parseUuid(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
