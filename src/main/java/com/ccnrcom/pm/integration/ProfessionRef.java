/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.pm.integration;

import com.ccnrcom.pm.util.JsonUtil;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;

/**
 * CCNR-RP 的「职业（角色）」引用：id + 显示名。
 *
 * <h2>为什么是一个纯数据 record</h2>
 * 它跨了三个地方：服务端从 CCNR-RP 反射取出的职业表、网络包里下发的列表、
 * 客户端角色选择弹窗里显示的行。三处各写一份解析必然漂移，
 * 因此编解码只有这一份实现（与 {@code TicketPage} / {@code TicketBoard} 同一思路），
 * 坏输入一律降级为空列表——角色列表解析失败不该让弹窗炸掉。
 *
 * <p>本类**无 MC 依赖**，服务端与客户端共用，可直接单测。
 */
public record ProfessionRef(String id, String name) {

    public ProfessionRef {
        id = id == null ? "" : id;
        name = name == null || name.isBlank() ? id : name;
    }

    /** 编码为 JSON 数组字符串（网络载荷）。 */
    public static String toJson(List<ProfessionRef> roles) {
        JsonArray arr = new JsonArray();
        if (roles != null) {
            for (ProfessionRef r : roles) {
                if (r == null || r.id().isBlank()) continue; // 没有 id 的条目无法被选，直接不传
                JsonObject o = new JsonObject();
                o.addProperty("id", r.id());
                o.addProperty("name", r.name());
                arr.add(o);
            }
        }
        return arr.toString();
    }

    /** 解析网络载荷；任何坏输入都降级为空列表。 */
    public static List<ProfessionRef> fromJson(String json) {
        List<ProfessionRef> out = new ArrayList<>();
        if (json == null || json.isBlank()) return out;
        try {
            JsonElement el = JsonUtil.GSON.fromJson(json, JsonElement.class);
            if (el == null || !el.isJsonArray()) return out;
            for (JsonElement e : el.getAsJsonArray()) {
                if (!e.isJsonObject()) continue;
                JsonObject o = e.getAsJsonObject();
                String id = JsonUtil.str(o, "id", "");
                if (id.isBlank()) continue;
                out.add(new ProfessionRef(id, JsonUtil.str(o, "name", id)));
            }
        } catch (Exception e) {
            out.clear();
        }
        return out;
    }
}
