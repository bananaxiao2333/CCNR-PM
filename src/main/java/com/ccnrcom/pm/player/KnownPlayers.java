/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.pm.player;

import com.ccnrcom.pm.util.JsonUtil;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * 「本服见过面的玩家」名录（服务端）。
 *
 * <h2>它解决什么</h2>
 * 工单的「关联玩家」是自由输入。原来**只校验格式**，于是玩家随手打一个不存在的名字也能建单——
 * 管理员拿到一张指向空气的工单，既查不到人也无从受理（用户提出「为什么不校验目标是否合法」）。
 * 但直接要求「必须在线」又会误伤真实场景：人刚下线、或改名了，照样得能举报。
 *
 * <p>因此判据定为：**在线 或 本服见过面**。名录在每次登录时登记（UUID + 大小写规范的名字 + 时间），
 * 落盘到 {@code config/ccnr_pm/known_players.json}，重启后仍有效。
 *
 * <h2>为什么不用别的现成数据</h2>
 * <ul>
 *   <li>在线的 {@code PlayerList} —— 只知道此刻在线的人，下线就没了（正是要避免的误伤）；</li>
 *   <li>头像缓存 {@code AvatarService} —— 只装「皮肤抓到了」的人：没皮肤的玩家恰恰不在里面，
 *       而且它按容量淘汰；拿它当名录会得到「有皮肤的人才算合法」这种荒谬结论；</li>
 *   <li>原版 {@code usercache.json} —— 依赖对方文件的格式与清理时机（约一个月就清），
 *       而且它由原版维护，我们读它等于把「谁是合法玩家」的判断权交给别人。</li>
 * </ul>
 *
 * <h2>线程与写入</h2>
 * 只在服务端主线程读写；落盘用 {@link JsonUtil#atomicWrite} 且**节流**（30s），
 * 关服时强制写一次（对称清理）。容量上限 2000，超出按最后见面时间淘汰最旧的。
 */
public final class KnownPlayers {

    private static final Logger LOGGER = LogManager.getLogger("ccnr_pm");

    /** 名录容量上限（超出按最后见面时间淘汰）。 */
    private static final int MAX_ENTRIES = 2000;

    /** 写盘节流：两次写盘至少间隔这么久。 */
    private static final long WRITE_THROTTLE_MS = 30_000L;

    /** 小写名字 → 记录。 */
    private static final Map<String, Record> BY_NAME = new ConcurrentHashMap<>();

    /** UUID → 小写名字（用于按 UUID 反查显示名）。 */
    private static final Map<UUID, String> NAME_BY_UUID = new ConcurrentHashMap<>();

    private record Record(UUID uuid, String name, long lastSeen) {}

    private static volatile Path file;
    private static volatile long lastWriteMs;
    private static volatile boolean dirty;

    private KnownPlayers() {}

    /** 载入名录（配置文件目录为 null 时退化为纯内存，仍然可用）。 */
    public static void init(Path configDir) {
        file = configDir == null ? null : configDir.resolve("known_players.json");
        BY_NAME.clear();
        NAME_BY_UUID.clear();
        JsonUtil.readObject(file).ifPresent(root -> {
            if (!root.has("players") || !root.get("players").isJsonArray()) return;
            for (JsonElement el : root.getAsJsonArray("players")) {
                if (!el.isJsonObject()) continue;
                JsonObject o = el.getAsJsonObject();
                String name = JsonUtil.str(o, "name", "");
                String uuid = JsonUtil.str(o, "uuid", "");
                if (name.isBlank() || uuid.isBlank()) continue;
                try {
                    remember(UUID.fromString(uuid), name, JsonUtil.num(o, "lastSeen", 0L), false);
                } catch (IllegalArgumentException ignored) {
                    // 坏条目跳过，不影响其它条目
                }
            }
            LOGGER.info("[CCNR-PM] 已载入 {} 个见过的玩家（关联玩家校验用）", BY_NAME.size());
        });
    }

    /** 关服：强制落盘（对称清理）。 */
    public static void shutdown() {
        save(true);
        BY_NAME.clear();
        NAME_BY_UUID.clear();
    }

    /** 玩家登录时登记。 */
    public static void remember(UUID uuid, String name) {
        remember(uuid, name, System.currentTimeMillis(), true);
    }

    private static void remember(UUID uuid, String name, long lastSeen, boolean write) {
        if (uuid == null || name == null || name.isBlank()) return;
        String key = key(name);
        BY_NAME.put(key, new Record(uuid, name, lastSeen));
        NAME_BY_UUID.put(uuid, key);
        if (!write) return;
        dirty = true;
        evictIfNeeded();
        save(false);
    }

    /** 该名字是否见过（大小写不敏感）。 */
    public static boolean isKnown(String name) {
        return name != null && BY_NAME.containsKey(key(name));
    }

    /** 名字 → UUID（大小写不敏感）；没见过返回空。 */
    public static Optional<UUID> uuidOf(String name) {
        if (name == null) return Optional.empty();
        Record r = BY_NAME.get(key(name));
        return r == null ? Optional.empty() : Optional.of(r.uuid());
    }

    /** 名录里登记时用的规范名字（手输的大小写可能与真实名字不同）。 */
    public static String canonicalName(String name) {
        if (name == null) return "";
        Record r = BY_NAME.get(key(name));
        return r == null ? name : r.name();
    }

    public static int size() {
        return BY_NAME.size();
    }

    /** 最后见面时间（诊断/排查用）；没见过返回 0。 */
    public static long lastSeen(String name) {
        Record r = name == null ? null : BY_NAME.get(key(name));
        return r == null ? 0L : r.lastSeen();
    }

    private static String key(String name) {
        return name.trim().toLowerCase(Locale.ROOT);
    }

    private static void evictIfNeeded() {
        if (BY_NAME.size() <= MAX_ENTRIES) return;
        List<Map.Entry<String, Record>> sorted = new ArrayList<>(BY_NAME.entrySet());
        sorted.sort(Comparator.comparingLong(e -> e.getValue().lastSeen()));
        int remove = BY_NAME.size() - MAX_ENTRIES;
        for (int i = 0; i < remove && i < sorted.size(); i++) {
            Map.Entry<String, Record> e = sorted.get(i);
            BY_NAME.remove(e.getKey());
            NAME_BY_UUID.remove(e.getValue().uuid());
        }
    }

    /** 落盘（{@code force=false} 时受节流限制）。 */
    private static void save(boolean force) {
        Path f = file;
        if (f == null || !dirty) return;
        long now = System.currentTimeMillis();
        if (!force && now - lastWriteMs < WRITE_THROTTLE_MS) return;

        JsonObject root = new JsonObject();
        root.addProperty("version", 1);
        com.google.gson.JsonArray arr = new com.google.gson.JsonArray();
        for (Record r : BY_NAME.values()) {
            JsonObject o = new JsonObject();
            o.addProperty("uuid", r.uuid().toString());
            o.addProperty("name", r.name());
            o.addProperty("lastSeen", r.lastSeen());
            arr.add(o);
        }
        root.add("players", arr);
        if (JsonUtil.atomicWrite(f, root)) {
            lastWriteMs = now;
            dirty = false;
        }
    }

    /** 诊断用：一行概览（{@code /pm status}）。 */
    public static String describe() {
        return "entries=" + BY_NAME.size() + "/" + MAX_ENTRIES + " file=" + (file == null ? "?" : file.toString())
                + " exists=" + (file != null && Files.isRegularFile(file));
    }
}
