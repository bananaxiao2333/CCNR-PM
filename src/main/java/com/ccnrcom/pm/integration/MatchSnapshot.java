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
import java.util.Optional;

/**
 * CCNR-RP 的**对局快照**：对局状态 + 幕表 + 事件表 + 当局事件流（纯数据 + JSON 编解码，无 MC 依赖）。
 *
 * <h2>为什么要有一个「快照」类型，而不是各画各的</h2>
 * 这份数据跨了四个地方：服务端反射读 CCNR-RP、网络包下发、对局页签渲染、事件页签渲染。
 * 四处各写一份解析必然漂移（对方改了字段名，表现是「某个数字永远是 0」这种没人会注意的静默失真）。
 * 因此编解码只有这一份实现，与 {@code TicketPage} / {@code ProfessionRef} 同一思路。
 *
 * <h2>哪些是对方的、哪些是我们的</h2>
 * {@link #match} 是 CCNR-RP 的 {@code EventManager.matchStatePayload()} **原样转存**——
 * 「现在第几幕 / 还剩多久 / 在跑哪些事件」只有它一个权威源，这里绝不再算一遍。
 * {@link #phases} / {@link #events} / {@link #log} 是 PM 额外补的两张表（对方没有把「可切换的全部幕」
 * 与「可按 id 触发的事件」放进同一个载荷），补法同样只是读对方的公开访问器，不含任何对局规则。
 *
 * <h2>计时</h2>
 * 对方只传**剩余毫秒**（{@code phaseTimerMs}）与它自己的时钟（{@code serverNowMs}），由客户端本地倒数：
 * 每秒发包会把「面板打开」变成一条持续流量，而用服务器时钟直接算剩余又会受两端时钟偏差影响。
 * 因此这里记下**收到时刻** {@link #receivedAtMs()}，界面用
 * {@code phaseTimerMs - (now - receivedAtMs)} 自己减——这正是对方客户端面板的算法。
 *
 * <p><b>坏输入一律降级</b>：本类型的数据来自外部模组，对方改了结构、字段缺失、类型不对都是常态。
 * 解析失败的条目**跳过而不是抛异常**，整包解析不出来时返回 {@link Optional#empty()}（调用方保留上一份快照）。
 */
public record MatchSnapshot(
        boolean available,
        JsonObject match,
        List<Phase> phases,
        List<Event> events,
        List<LogLine> log,
        long receivedAtMs) {

    /** 幕表的一行：id + 显示名（名字为空时回退 id，与对方 {@code DisplayInfo.nameOr} 同一语义）。 */
    public record Phase(String id, String name) {
        public Phase {
            id = id == null ? "" : id;
            name = name == null || name.isBlank() ? id : name;
        }
    }

    /**
     * 事件表的一行。
     *
     * <p>{@code state} 用对方 {@code EventState} 枚举的**名字**原样传递（{@code SCHEDULED}/{@code RUNNING}/
     * {@code SETTLED}），不翻译成 PM 自己的枚举：翻译多一层就多一处会漂移的映射，
     * 而这三个词本来就是要显示给人看的。
     */
    public record Event(String id, String name, String state, boolean enabled) {
        public Event {
            id = id == null ? "" : id;
            name = name == null || name.isBlank() ? id : name;
            state = state == null || state.isBlank() ? "SCHEDULED" : state;
        }

        public boolean running() {
            return "RUNNING".equals(state);
        }

        /** 可按 id 强制触发：未启用或已在运行的事件对方会直接回绝，界面据此置灰（判定仍在服务端）。 */
        public boolean triggerable() {
            return enabled && "SCHEDULED".equals(state);
        }

        /** 可按 id 强制结束：只有正在运行的事件能结束。 */
        public boolean endable() {
            return running();
        }
    }

    /** 当局事件流的一行（击杀/死亡/阶段推进/结算……）。 */
    public record LogLine(long atMs, String kind, String text) {
        public LogLine {
            kind = kind == null ? "" : kind;
            text = text == null ? "" : text;
        }

        /** 事件种类 → 短标签（{@code EVENT_KIND_CHARACTER_KILL} → {@code CHARACTER_KILL}）。 */
        public String shortKind() {
            return kind.startsWith("EVENT_KIND_") ? kind.substring("EVENT_KIND_".length()) : kind;
        }
    }

    public MatchSnapshot {
        match = match == null ? new JsonObject() : match;
        phases = phases == null ? List.of() : List.copyOf(phases);
        events = events == null ? List.of() : List.copyOf(events);
        log = log == null ? List.of() : List.copyOf(log);
    }

    /** 「服务端没有 CCNR-RP」（或对方还没初始化完）。此时一切读数无意义，界面只显示一行说明。 */
    public static MatchSnapshot absent() {
        return new MatchSnapshot(false, null, List.of(), List.of(), List.of(), System.currentTimeMillis());
    }

    // ------------------------------------------------------------------
    // 派生读数（全部读自 {@link #match}，不在 PM 侧重算）
    // ------------------------------------------------------------------

    /** 剧本是否在运行中（{@code /rp end} 之后为 false，等开局事件重启）。 */
    public boolean running() {
        return JsonUtil.bool(match, "running", false);
    }

    /** 当前模式显示名；未激活或对方没配名字时为空串（界面回退本地化占位，不显示空括号）。 */
    public String modeName() {
        return JsonUtil.str(nested("mode"), "name", "");
    }

    public String phaseId() {
        return JsonUtil.str(nested("phase"), "id", "");
    }

    public String phaseName() {
        return JsonUtil.str(nested("phase"), "name", "");
    }

    /** 当前幕序号（0 起）；无阶段时为 -1。 */
    public int phaseIndex() {
        return (int) JsonUtil.num(nested("phase"), "index", -1);
    }

    public int phaseTotal() {
        return (int) JsonUtil.num(nested("phase"), "total", 0);
    }

    /** 当前幕是否条件驱动（不按时长推进，由触发器命中时切幕）。 */
    public boolean phaseConditionDriven() {
        return JsonUtil.bool(nested("phase"), "conditionDriven", false);
    }

    /** 时长驱动幕的剩余毫秒；对方没写该键（条件驱动/无阶段）时返回 -1。 */
    public long phaseTimerMs() {
        return JsonUtil.num(match, "phaseTimerMs", -1L);
    }

    /** 行为序列「下一步还有多久」的毫秒数；无运行中序列时返回 -1。 */
    public long seqTimerMs() {
        return JsonUtil.num(match, "seqTimerMs", -1L);
    }

    /**
     * 按本地时钟推算的当前幕剩余毫秒（收到快照时起算）。
     *
     * @return 剩余毫秒（不小于 0）；对方没有倒计时时返回 -1
     */
    public long phaseRemainMs(long nowMs) {
        long total = phaseTimerMs();
        if (total < 0) return -1L;
        return Math.max(0L, total - Math.max(0L, nowMs - receivedAtMs));
    }

    /** 同上，用于行为序列计时。 */
    public long seqRemainMs(long nowMs) {
        long total = seqTimerMs();
        if (total < 0) return -1L;
        return Math.max(0L, total - Math.max(0L, nowMs - receivedAtMs));
    }

    /** 进行中事件的显示名（对方 {@code match.events[]}，已带展示三件套）。 */
    public List<String> activeEventNames() {
        List<String> out = new ArrayList<>();
        JsonObject m = match;
        if (!m.has("events") || !m.get("events").isJsonArray()) return out;
        for (JsonElement el : m.getAsJsonArray("events")) {
            if (!el.isJsonObject()) continue;
            String name = JsonUtil.str(el.getAsJsonObject(), "name", "");
            if (name.isBlank()) name = JsonUtil.str(el.getAsJsonObject(), "id", "");
            if (!name.isBlank()) out.add(name);
        }
        return out;
    }

    private JsonObject nested(String key) {
        if (!match.has(key) || !match.get(key).isJsonObject()) return null;
        return match.getAsJsonObject(key);
    }

    // ------------------------------------------------------------------
    // 编解码
    // ------------------------------------------------------------------

    /**
     * 解析服务端下发的载荷。
     *
     * @return 解析失败（不是 JSON / 不是对象）时为空；调用方**保留上一份快照**而不是清空界面
     */
    public static Optional<MatchSnapshot> fromJson(String json) {
        if (json == null || json.isBlank()) return Optional.empty();
        JsonElement root;
        try {
            root = JsonUtil.GSON.fromJson(json, JsonElement.class);
        } catch (Exception e) {
            return Optional.empty();
        }
        if (root == null || !root.isJsonObject()) return Optional.empty();
        JsonObject o = root.getAsJsonObject();

        boolean available = JsonUtil.bool(o, "available", false);
        if (!available) return Optional.of(absent());

        return Optional.of(new MatchSnapshot(
                true,
                o.has("match") && o.get("match").isJsonObject() ? o.getAsJsonObject("match") : new JsonObject(),
                phasesOf(o),
                eventsOf(o),
                logOf(o),
                System.currentTimeMillis()));
    }

    private static List<Phase> phasesOf(JsonObject root) {
        List<Phase> out = new ArrayList<>();
        for (JsonObject e : objectsOf(root, "phases")) {
            String id = JsonUtil.str(e, "id", "");
            if (id.isBlank()) continue; // 没有 id 的幕无法被切换，不显示
            out.add(new Phase(id, JsonUtil.str(e, "name", id)));
        }
        return out;
    }

    private static List<Event> eventsOf(JsonObject root) {
        List<Event> out = new ArrayList<>();
        for (JsonObject e : objectsOf(root, "events")) {
            String id = JsonUtil.str(e, "id", "");
            if (id.isBlank()) continue;
            out.add(new Event(
                    id,
                    JsonUtil.str(e, "name", id),
                    JsonUtil.str(e, "state", "SCHEDULED"),
                    JsonUtil.bool(e, "enabled", false)));
        }
        return out;
    }

    private static List<LogLine> logOf(JsonObject root) {
        List<LogLine> out = new ArrayList<>();
        for (JsonObject e : objectsOf(root, "log")) {
            String text = JsonUtil.str(e, "text", "");
            String kind = JsonUtil.str(e, "kind", "");
            // 只有**正文**是不可省的那一列：少了它这一行等于一行空白。
            // 反过来，只有正文而没种类照样显示（种类只是标签，没有它仍看得出发生了什么）。
            if (text.isBlank()) continue;
            out.add(new LogLine(JsonUtil.num(e, "at", 0L), kind, text));
        }
        return out;
    }

    /** 取数组字段里的对象元素；非数组/元素不是对象一律跳过（外部数据的常态）。 */
    private static List<JsonObject> objectsOf(JsonObject root, String key) {
        List<JsonObject> out = new ArrayList<>();
        if (root == null || !root.has(key) || !root.get(key).isJsonArray()) return out;
        JsonArray arr = root.getAsJsonArray(key);
        for (JsonElement el : arr) {
            if (el != null && el.isJsonObject()) out.add(el.getAsJsonObject());
        }
        return out;
    }
}
