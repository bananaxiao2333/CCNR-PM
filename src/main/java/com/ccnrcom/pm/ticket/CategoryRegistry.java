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
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 举报类别注册表（纯类，无 MC 依赖，可直接单测）。
 *
 * <p>生命周期：服务端启动时从 {@code config/ccnr_pm/report_categories.json} 载入 → 放入 {@link #install(CategoryRegistry)}
 * 的静态槽位 → 之后 S2C 开工单面板时把**启用中**的类别随包下发。客户端不自己读文件， 避免「客户端与服务端类别不一致 → 玩家选了一个服务端不认的类别」这类只在联机时才暴露的缺陷。
 *
 * <p>为什么保留 {@code defaults()}：文件缺失/损坏时若返回空表，玩家会看到一个没有任何类别的空面板，
 * 且没有任何线索说明原因。回退到内置六类可以让功能立刻可用，同时把「文件有问题」交给日志。
 */
public final class CategoryRegistry {

    /** 内置默认类别 id（与语言包 {@code ccnr_pm.category.<id>.name} 一一对应，LangFileTest 会校验）。 */
    public static final List<String> DEFAULT_IDS =
            List.of("chat_abuse", "cheating", "griefing", "spam", "roleplay", "other");

    private final List<TicketCategory> categories;
    private final Map<String, TicketCategory> byId;

    private static volatile CategoryRegistry active = new CategoryRegistry(defaults());

    public CategoryRegistry(List<TicketCategory> categories) {
        // 按 order 排序，同 order 保持写入顺序（LinkedHashMap/稳定排序保证管理员可预期）
        List<TicketCategory> sorted = new ArrayList<>(categories == null ? List.of() : categories);
        sorted.sort(Comparator.comparingInt(TicketCategory::order));
        this.categories = List.copyOf(sorted);
        Map<String, TicketCategory> map = new LinkedHashMap<>();
        for (TicketCategory c : this.categories) {
            map.put(c.id(), c);
        }
        this.byId = Map.copyOf(map);
    }

    /** 当前生效的注册表（服务端启动/重载时替换）。 */
    public static CategoryRegistry active() {
        return active;
    }

    /** 安装新注册表；空表视为无效并保持原值（避免一次坏重载把面板清空）。 */
    public static void install(CategoryRegistry next) {
        if (next != null && !next.all().isEmpty()) {
            active = next;
        }
    }

    /** 全部类别（含已停用）。 */
    public List<TicketCategory> all() {
        return categories;
    }

    /** 面板可选类别（已启用）。 */
    public List<TicketCategory> enabled() {
        return categories.stream().filter(TicketCategory::enabled).toList();
    }

    public Optional<TicketCategory> byId(String id) {
        return id == null ? Optional.empty() : Optional.ofNullable(byId.get(id));
    }

    /** id 是否是一个**已启用**的合法类别（提交校验用；停用类别不接受新工单）。 */
    public boolean isSelectable(String id) {
        return byId(id).map(TicketCategory::enabled).orElse(false);
    }

    /** 内置六类（文件缺失/损坏时的回退）。 */
    public static List<TicketCategory> defaults() {
        List<TicketCategory> list = new ArrayList<>();
        int order = 10;
        for (String id : DEFAULT_IDS) {
            list.add(new TicketCategory(id, null, order, true));
            order += 10;
        }
        return list;
    }

    /**
     * 解析配置文件。容忍缺字段（用默认值补齐）；id 重复时后者覆盖前者并保持首次出现的位置， 这样管理员手工合并文件时不会因重复项导致整个注册表失效。
     *
     * @return 解析结果；{@code root} 为空或无有效条目时返回 {@code null}，由调用方决定回退策略
     */
    public static CategoryRegistry parse(JsonObject root) {
        if (root == null || !root.has("categories") || !root.get("categories").isJsonArray()) {
            return null;
        }
        JsonArray arr = root.getAsJsonArray("categories");
        Map<String, TicketCategory> merged = new LinkedHashMap<>();
        int autoOrder = 10;
        for (JsonElement el : arr) {
            if (!el.isJsonObject()) continue;
            JsonObject o = el.getAsJsonObject();
            String id = JsonUtil.str(o, "id", "").trim();
            if (id.isEmpty()) continue;
            String name = JsonUtil.str(o, "name", null);
            int order = (int) JsonUtil.num(o, "order", autoOrder);
            boolean enabled = JsonUtil.bool(o, "enabled", true);
            merged.put(id, new TicketCategory(id, name, order, enabled));
            autoOrder += 10;
        }
        if (merged.isEmpty()) return null;
        return new CategoryRegistry(new ArrayList<>(merged.values()));
    }
}
