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
import java.util.UUID;

/**
 * 工单的一个「关联玩家」。
 *
 * <p>为什么叫「关联玩家」而不是「被举报玩家」：一张工单里被牵涉的往往不止一个人
 * （动手的、放风的、在旁边怂恿的），把它们都记下来比逼玩家只挑一个更有用。
 * 语义上也更中性——它只表示「这张工单和谁有关」，不预设谁有错。
 *
 * <p>{@code uuid} 允许为 {@code null}：玩家是**手输**的（autocomplete 只做补全，不强制从列表里选），
 * 因此可能写出一个当时不在线的名字。此时仍然把名字存下来——工单本身有价值，
 * 没必要因为「查不到这个人在线」就拒绝整张工单。
 *
 * @param uuid 在线时解析到的 UUID；手输且查不到时为 null
 * @param name 玩家名（提交时的快照）
 */
public record TicketTarget(UUID uuid, String name) {

    public TicketTarget {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("关联玩家名不可为空");
        name = name.trim();
    }

    /** 只有名字（未能解析到在线玩家）。 */
    public static TicketTarget byName(String name) {
        return new TicketTarget(null, name);
    }

    /** 序列化为 JSON 数组字符串（DB 的 TEXT 列与文件后端共用）。 */
    public static String listToJson(List<TicketTarget> targets) {
        JsonArray arr = new JsonArray();
        if (targets != null) {
            for (TicketTarget t : targets) {
                JsonObject o = new JsonObject();
                o.addProperty("uuid", t.uuid() == null ? null : t.uuid().toString());
                o.addProperty("name", t.name());
                arr.add(o);
            }
        }
        return JsonUtil.GSON.toJson(arr);
    }

    /** 从 JSON 数组字符串还原；损坏/空值返回空列表（不让一条坏行拖垮整张工单）。 */
    public static List<TicketTarget> listFromJson(String json) {
        List<TicketTarget> out = new ArrayList<>();
        if (json == null || json.isBlank()) return List.copyOf(out);
        try {
            JsonElement el = JsonUtil.GSON.fromJson(json, JsonElement.class);
            if (el == null || !el.isJsonArray()) return List.copyOf(out);
            for (JsonElement e : el.getAsJsonArray()) {
                if (!e.isJsonObject()) continue;
                JsonObject o = e.getAsJsonObject();
                String name = JsonUtil.str(o, "name", "").trim();
                if (name.isEmpty()) continue;
                out.add(new TicketTarget(parseUuid(JsonUtil.str(o, "uuid", null)), name));
            }
        } catch (Exception ignored) {
            // 解析失败按空列表处理，由调用方决定是否记录
        }
        return List.copyOf(out);
    }

    /** 面向显示的逗号分隔名字串；无关联玩家时返回空串。 */
    public static String joinNames(List<TicketTarget> targets) {
        if (targets == null || targets.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (TicketTarget t : targets) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(t.name());
        }
        return sb.toString();
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
