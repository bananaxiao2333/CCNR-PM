/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.pm.ticket;

import com.ccnrcom.pm.util.JsonUtil;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * 文件工单仓储（{@code world/ccnr_pm/tickets.json}）。
 *
 * <p>定位：**未配置数据库时的默认后端**。整表常驻内存 + 每次变更原子写盘。
 *
 * <p>边界（务必知悉）：这是为中小服设计的。内存占用与单次写盘耗时都随工单数线性增长，
 * 万级以上工单应改用数据库后端（{@code config/ccnr_pm/db.properties} 里开启）。本类不做分页读盘，
 * 因为它存在的意义就是「零部署可用」，而不是替代数据库。
 *
 * <p>并发：所有读写都在同一把监视器锁内，保证「分配序号 + 追加 + 落盘」是一个原子步骤。
 */
public final class JsonTicketStore implements TicketStore {

    private static final Logger LOGGER = LogManager.getLogger("ccnr_pm");
    private static final int VERSION = 1;
    private static final int MAX_LOAD = 100_000;

    private final Path file;
    private final Object lock = new Object();
    private final List<Ticket> tickets = new ArrayList<>();

    /** 管理员 uuid → 被其忽略的工单 id 集合。与工单同文件存放，共用同一次原子写。 */
    private final java.util.Map<String, java.util.Set<String>> ignores = new java.util.HashMap<>();

    public JsonTicketStore(Path file) {
        this.file = file;
    }

    @Override
    public String backend() {
        return "json";
    }

    @Override
    public boolean init() {
        synchronized (lock) {
            tickets.clear();
            Optional<JsonObject> root = JsonUtil.readObject(file);
            if (root.isEmpty()) {
                // 文件不存在是正常的首次启动；文件存在但读不出来时 JsonUtil 已备份 .bak 并记日志
                return true;
            }
            JsonObject o = root.get();
            if (!o.has("tickets") || !o.get("tickets").isJsonArray()) return true;
            JsonArray arr = o.getAsJsonArray("tickets");
            int n = 0;
            for (var el : arr) {
                if (!el.isJsonObject()) continue;
                if (n++ >= MAX_LOAD) {
                    LOGGER.error("[CCNR-PM] 工单文件条目超过 {}，已截断载入——请改用数据库后端", MAX_LOAD);
                    break;
                }
                Ticket t = parse(el.getAsJsonObject());
                if (t != null) tickets.add(t);
            }
            ignores.clear();
            if (o.has("ignores") && o.get("ignores").isJsonObject()) {
                com.google.gson.JsonObject ig = o.getAsJsonObject("ignores");
                for (var e : ig.entrySet()) {
                    if (!e.getValue().isJsonArray()) continue;
                    java.util.Set<String> ids = new java.util.LinkedHashSet<>();
                    for (var el : e.getValue().getAsJsonArray()) {
                        try {
                            String id = el.getAsString();
                            if (!id.isBlank()) ids.add(id);
                        } catch (Exception ignored) {
                            // 单条坏数据跳过
                        }
                    }
                    if (!ids.isEmpty()) ignores.put(e.getKey(), ids);
                }
            }
            LOGGER.info("[CCNR-PM] 已载入 {} 条工单（文件后端）", tickets.size());
            return true;
        }
    }

    @Override
    public Optional<Ticket> insert(NewTicket n) {
        if (n == null) return Optional.empty();
        synchronized (lock) {
            // 身份就是 UUID：不再有「取 max+1」这一步，也就没有可重复的编号来源
            Ticket created = Ticket.create(
                    TicketId.newId(),
                    n.reporterUuid(),
                    n.reporterName(),
                    n.targets(),
                    n.categoryId(),
                    n.content(),
                    n.detail(),
                    n.createdAt());
            tickets.add(created);
            if (!save()) {
                // 落盘失败就把内存里的那条撤掉：宁可让玩家重试，也不要出现「面板说成功、重启后消失」
                tickets.remove(created);
                LOGGER.error("[CCNR-PM] 工单 {} 落盘失败，已回滚内存条目", created.label());
                return Optional.empty();
            }
            return Optional.of(created);
        }
    }

    @Override
    public Optional<Ticket> byId(String id) {
        if (id == null || id.isBlank()) return Optional.empty();
        // 走 TicketId 的归一化比较：玩家可能带连字符、也可能大写、也可能直接粘贴 32 位无连字符形式
        String q = TicketId.normalize(id);
        synchronized (lock) {
            return tickets.stream()
                    .filter(t -> TicketId.normalize(t.id()).equals(q))
                    .findFirst();
        }
    }

    @Override
    public List<Ticket> byIdPrefix(String prefix, int limit) {
        if (prefix == null || prefix.isBlank()) return List.of();
        synchronized (lock) {
            return tickets.stream()
                    .filter(t -> TicketId.matches(t.id(), prefix))
                    .limit(Math.max(1, limit))
                    .toList();
        }
    }

    @Override
    public List<Ticket> list(TicketStatus filter, int limit, int offset) {
        synchronized (lock) {
            return tickets.stream()
                    .filter(t -> filter == null || t.status() == filter)
                    .sorted(Comparator.comparingLong(Ticket::createdAt)
                            .thenComparing(Ticket::id)
                            .reversed())
                    .skip(Math.max(0, offset))
                    .limit(Math.max(1, limit))
                    .toList();
        }
    }

    @Override
    public List<Ticket> listActive(int limit) {
        synchronized (lock) {
            return tickets.stream()
                    .filter(t -> t.status().isUnresolved())
                    .sorted(Comparator.comparingLong(Ticket::createdAt)
                            .thenComparing(Ticket::id)
                            .reversed())
                    .limit(Math.max(1, limit))
                    .toList();
        }
    }

    @Override
    public int count(TicketStatus filter) {
        synchronized (lock) {
            if (filter == null) return tickets.size();
            return (int) tickets.stream().filter(t -> t.status() == filter).count();
        }
    }

    @Override
    public int countUnresolvedByReporter(UUID reporterUuid) {
        if (reporterUuid == null) return 0;
        synchronized (lock) {
            return (int) tickets.stream()
                    .filter(t ->
                            reporterUuid.equals(t.reporterUuid()) && t.status().isUnresolved())
                    .count();
        }
    }

    @Override
    public long lastSubmitAt(UUID reporterUuid) {
        if (reporterUuid == null) return 0L;
        synchronized (lock) {
            long max = 0L;
            for (Ticket t : tickets) {
                if (reporterUuid.equals(t.reporterUuid()) && t.createdAt() > max) max = t.createdAt();
            }
            return max;
        }
    }

    @Override
    public boolean updateStatus(String id, TicketStatus status, String handler, long when, String note) {
        if (id == null || id.isBlank()) return false;
        synchronized (lock) {
            for (int i = 0; i < tickets.size(); i++) {
                Ticket t = tickets.get(i);
                if (!TicketId.normalize(t.id()).equals(TicketId.normalize(id))) continue;
                Ticket old = t;
                tickets.set(i, t.withStatus(status, handler, when, note));
                if (!save()) {
                    tickets.set(i, old);
                    return false;
                }
                return true;
            }
            return false;
        }
    }

    @Override
    public java.util.Set<String> ignoredIds(UUID adminUuid) {
        if (adminUuid == null) return java.util.Set.of();
        synchronized (lock) {
            return java.util.Set.copyOf(ignores.getOrDefault(adminUuid.toString(), java.util.Set.of()));
        }
    }

    @Override
    public boolean setIgnored(UUID adminUuid, String ticketId, boolean ignored) {
        if (adminUuid == null || ticketId == null || ticketId.isBlank()) return false;
        synchronized (lock) {
            String key = adminUuid.toString();
            java.util.Set<String> set = new java.util.LinkedHashSet<>(ignores.getOrDefault(key, java.util.Set.of()));
            boolean changed = ignored ? set.add(ticketId) : set.remove(ticketId);
            if (!changed) return false;
            if (set.isEmpty()) {
                ignores.remove(key);
            } else {
                ignores.put(key, set);
            }
            if (!save()) {
                // 落盘失败就回滚内存，避免「界面说忽略了、重启后又冒出来」
                if (ignored) {
                    set.remove(ticketId);
                } else {
                    set.add(ticketId);
                }
                if (set.isEmpty()) ignores.remove(key);
                else ignores.put(key, set);
                return false;
            }
            return true;
        }
    }

    @Override
    public void close() {
        synchronized (lock) {
            save();
        }
    }

    /** 原子写盘。 */
    private boolean save() {
        JsonObject root = new JsonObject();
        root.addProperty("version", VERSION);
        JsonArray arr = new JsonArray();
        for (Ticket t : tickets) arr.add(toJson(t));
        root.add("tickets", arr);

        com.google.gson.JsonObject ig = new com.google.gson.JsonObject();
        for (var e : ignores.entrySet()) {
            com.google.gson.JsonArray ids = new com.google.gson.JsonArray();
            for (String id : e.getValue()) ids.add(id);
            ig.add(e.getKey(), ids);
        }
        root.add("ignores", ig);
        return JsonUtil.atomicWrite(file, root);
    }

    private static JsonObject toJson(Ticket t) {
        JsonObject o = new JsonObject();
        o.addProperty("id", t.id());
        o.addProperty(
                "reporterUuid",
                t.reporterUuid() == null ? null : t.reporterUuid().toString());
        o.addProperty("reporterName", t.reporterName());
        // 关联玩家多值：整列存 JSON 数组（见 TicketTarget.listToJson）
        o.addProperty("targets", TicketTarget.listToJson(t.targets()));
        o.addProperty("category", t.categoryId());
        o.addProperty("content", t.content());
        o.addProperty("detail", t.detail());
        o.addProperty("status", t.status().id());
        o.addProperty("createdAt", t.createdAt());
        o.addProperty("handler", t.handlerName());
        o.addProperty("handledAt", t.handledAt());
        o.addProperty("handleNote", t.handleNote());
        return o;
    }

    private static Ticket parse(JsonObject o) {
        try {
            String id = JsonUtil.str(o, "id", "");
            if (id.isBlank()) return null;
            return new Ticket(
                    id,
                    uuid(JsonUtil.str(o, "reporterUuid", null)),
                    JsonUtil.str(o, "reporterName", ""),
                    TicketTarget.listFromJson(JsonUtil.str(o, "targets", null)),
                    JsonUtil.str(o, "category", ""),
                    JsonUtil.str(o, "content", ""),
                    JsonUtil.str(o, "detail", ""),
                    TicketStatus.parse(JsonUtil.str(o, "status", "open")),
                    JsonUtil.num(o, "createdAt", 0L),
                    JsonUtil.str(o, "handler", null),
                    JsonUtil.num(o, "handledAt", 0L),
                    JsonUtil.str(o, "handleNote", null));
        } catch (Exception e) {
            LOGGER.error("[CCNR-PM] 跳过损坏的工单条目: {}", e.toString());
            return null;
        }
    }

    private static UUID uuid(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** 供命令展示：文件路径。 */
    public String describe() {
        return "json:" + (file == null ? "?" : file.toString().toLowerCase(Locale.ROOT));
    }
}
