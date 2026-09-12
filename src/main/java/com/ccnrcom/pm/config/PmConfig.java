/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.pm.config;

import net.minecraftforge.common.ForgeConfigSpec;

/**
 * 服务端调参（{@code world/serverconfig/ccnr_pm-server.toml}）。
 *
 * <p>分层原则（沿用 CCNR 系列）：**调参**进 toml（本类）；**可选定义**（举报类别）进
 * {@code config/ccnr_pm/report_categories.json}，管理员可直接编辑；**运行时数据**（工单）进
 * {@code world/ccnr_pm/} 或数据库。
 *
 * <p>注册为 {@code ModConfig.Type.SERVER}：这些值影响服务端判定（长度、冷却、并发上限），
 * 必须每个世界独立且由服务端说了算，不能跟着玩家客户端走。
 */
public final class PmConfig {

    public static final ForgeConfigSpec SPEC;

    /** 举报内容长度上限（由 {@code /a} 消息预填，玩家可在此长度内编辑）。 */
    public static final ForgeConfigSpec.IntValue MAX_CONTENT;

    /** 举报详细描述长度上限。 */
    public static final ForgeConfigSpec.IntValue MAX_DETAIL;

    /** 同一玩家两次提交之间的冷却（秒）。 */
    public static final ForgeConfigSpec.IntValue COOLDOWN_SECONDS;

    /** 同一玩家同时未办结的工单上限。 */
    public static final ForgeConfigSpec.IntValue MAX_OPEN_PER_PLAYER;

    /** 新工单是否私聊提醒在线管理员。 */
    public static final ForgeConfigSpec.BooleanValue NOTIFY_ADMINS;

    /** 管理命令每页条数。 */
    public static final ForgeConfigSpec.IntValue LIST_PAGE_SIZE;

    /** 是否抓取并缓存玩家头像（关闭后通知卡片回退到客户端自己解析的皮肤）。 */
    public static final ForgeConfigSpec.BooleanValue AVATAR_ENABLED;

    /** 等待头像下载的时间上限（毫秒）；到点就用已有头像把卡片发出去，不阻塞提醒。 */
    public static final ForgeConfigSpec.IntValue AVATAR_TIMEOUT_MS;

    /**
     * 服务端拿不到皮肤信息时，是否按玩家名去 Mojang 反查皮肤。
     *
     * <p>背景：皮肤 URL 只存在于 {@code GameProfile.textures} 属性里，而**纯离线服务器**
     * （无正版验证、无皮肤插件）根本不会填这个属性——于是头像全变默认。这条回退会
     * 用玩家名查 Mojang 档案（两次 HTTP），正版名的玩家因此在离线服也能拿到真实头像；
     * 查不到（盗版名）就记一次「已知无头像」，不再重试。
     *
     * <p>代价是把玩家名发到 Mojang 的公开接口，因此做成开关（默认开）。
     */
    public static final ForgeConfigSpec.BooleanValue AVATAR_MOJANG_LOOKUP;

    static {
        // push/pop 必须严格配平：Builder.pop() 在上下文栈为空时会抛
        // IllegalArgumentException，而这里一旦抛出就是 ExceptionInInitializerError
        // ——模组**直接加载失败**，且编译期与常规单测都发现不了（见 StartupSmokeTest）。
        ForgeConfigSpec.Builder b = new ForgeConfigSpec.Builder();
        b.comment("举报工单").push("report");
        MAX_CONTENT = b.comment("举报内容长度上限").defineInRange("maxContent", 256, 16, 4096);
        MAX_DETAIL = b.comment("举报详细描述长度上限（0 表示不允许填写）").defineInRange("maxDetail", 512, 0, 8192);
        COOLDOWN_SECONDS = b.comment("同一玩家两次提交之间的冷却秒数（0 表示不限制）").defineInRange("cooldownSeconds", 60, 0, 86400);
        MAX_OPEN_PER_PLAYER = b.comment("同一玩家同时未办结的工单上限（0 表示不限制）").defineInRange("maxOpenPerPlayer", 3, 0, 100);
        NOTIFY_ADMINS = b.comment("新工单是否私聊提醒在线管理员").define("notifyAdmins", true);
        b.pop();
        b.comment("头像缓存（面向第三方/离线服务器：服务端主动抓取皮肤并随通知下发）").push("avatar");
        AVATAR_ENABLED = b.comment("是否抓取并缓存玩家头像").define("enabled", true);
        AVATAR_TIMEOUT_MS = b.comment("等待头像下载的时间上限（毫秒）；超时则用已有头像发卡片").defineInRange("timeoutMs", 3000, 200, 30000);
        AVATAR_MOJANG_LOOKUP = b.comment("拿不到 textures 属性时，按玩家名去 Mojang 反查皮肤（离线服上的正版名也能有真实头像；代价是把玩家名发给 Mojang）")
                .define("mojangLookup", true);
        b.pop();
        b.comment("管理命令").push("admin");
        LIST_PAGE_SIZE = b.comment("工单列表每页条数").defineInRange("listPageSize", 10, 1, 50);
        b.pop();
        SPEC = b.build();
    }

    private PmConfig() {}

    /** 当前限制（供 {@link com.ccnrcom.pm.ticket.ReportValidator} 使用）。 */
    public static com.ccnrcom.pm.ticket.ReportValidator.Limits limits() {
        return new com.ccnrcom.pm.ticket.ReportValidator.Limits(1, MAX_CONTENT.get(), MAX_DETAIL.get());
    }
}
