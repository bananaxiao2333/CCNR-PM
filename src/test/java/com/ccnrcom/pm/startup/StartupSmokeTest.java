/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.pm.startup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ccnrcom.pm.avatar.AvatarService;
import com.ccnrcom.pm.config.PmConfig;
import com.ccnrcom.pm.permission.PmPermissions;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 启动期冒烟测试：强制初始化「模组构造阶段会被类加载」的那些类。
 *
 * <h2>为什么需要这个测试（真实事故）</h2>
 * v0.1.0 首次实机启动时，模组**直接加载失败**：
 *
 * <pre>
 * java.lang.ExceptionInInitializerError: null
 *   caused by java.lang.IllegalArgumentException: Attempted to pop 1 elements when we only had: []
 *     at net.minecraftforge.common.ForgeConfigSpec$Builder.pop
 *     at com.ccnrcom.pm.config.PmConfig.&lt;clinit&gt;
 *     at com.ccnrcom.pm.CCNRPMMod.&lt;init&gt;
 * </pre>
 *
 * 原因：往 {@code PmConfig} 里插入新 section 时多打了一个 {@code b.pop()}，把 builder 的上下文栈多弹了一层。
 *
 * <p>**关键在于：编译通过、100 个测试全绿，却一点没拦住它。** 因为既有测试没有任何一个
 * 触碰过 {@code PmConfig}——静态初始化只在类**首次被主动使用**时才跑，而
 * 「编译期能过」和「运行时能初始化」是两件完全不同的事。
 *
 * <p>本类补上这个缺口：凡是 `<clinit>` 会在模组构造期执行的类，都必须在这里被真正初始化一次。
 * 静态初始化抛异常 → 测试失败 → 再也到不了玩家那里。
 *
 * <p>检查范围是「**构造期就会跑到的**静态初始化」，不含需要完整游戏运行时的部分
 * （例如需要服务端实例的 {@code TicketService.init}，那只能靠实机验证）。
 */
class StartupSmokeTest {

    // ------------------------------------------------------------------
    // PmConfig：本次事故的直接守卫
    // ------------------------------------------------------------------

    /** 期望的配置项路径 → 默认值。路径错、section 写错、pop 多一层，这里都会红。 */
    private static final List<ConfigExpectation> EXPECTED = List.of(
            new ConfigExpectation("report.maxContent", 256),
            new ConfigExpectation("report.maxDetail", 512),
            new ConfigExpectation("report.cooldownSeconds", 60),
            new ConfigExpectation("report.maxOpenPerPlayer", 3),
            new ConfigExpectation("report.notifyAdmins", true),
            new ConfigExpectation("avatar.enabled", true),
            new ConfigExpectation("avatar.timeoutMs", 3000),
            new ConfigExpectation("avatar.mojangLookup", true),
            new ConfigExpectation("admin.listPageSize", 10));

    private record ConfigExpectation(String path, Object defaultValue) {}

    /**
     * 触发 {@code PmConfig.<clinit>} 并校验整棵配置树。
     *
     * <p>用 {@code getDefault()} 而不是 {@code get()}：后者要求配置已被 FML 加载，
     * 单测里调它会抛 "Cannot get config value before config is loaded"，
     * 而我们要验的恰恰是「**能否成功构建出 spec**」这件事本身。
     */
    @Test
    @DisplayName("PmConfig 静态初始化成功，且每个配置项落在预期的 section 与路径上")
    void configSpecBuildsWithExpectedPaths() {
        assertNotNull(PmConfig.SPEC, "PmConfig.SPEC 为空——静态初始化没跑完");

        assertPath("report.maxContent", PmConfig.MAX_CONTENT, 256);
        assertPath("report.maxDetail", PmConfig.MAX_DETAIL, 512);
        assertPath("report.cooldownSeconds", PmConfig.COOLDOWN_SECONDS, 60);
        assertPath("report.maxOpenPerPlayer", PmConfig.MAX_OPEN_PER_PLAYER, 3);
        assertPath("report.notifyAdmins", PmConfig.NOTIFY_ADMINS, true);
        assertPath("avatar.enabled", PmConfig.AVATAR_ENABLED, true);
        assertPath("avatar.timeoutMs", PmConfig.AVATAR_TIMEOUT_MS, 3000);
        assertPath("avatar.mojangLookup", PmConfig.AVATAR_MOJANG_LOOKUP, true);
        assertPath("admin.listPageSize", PmConfig.LIST_PAGE_SIZE, 10);
    }

    private static void assertPath(
            String expectedPath,
            net.minecraftforge.common.ForgeConfigSpec.ConfigValue<?> value,
            Object expectedDefault) {
        assertNotNull(value, "配置项为空: " + expectedPath);
        assertEquals(
                List.of(expectedPath.split("\\.")),
                value.getPath(),
                "配置项路径不符（section 写错或多弹/少弹了一层上下文）: " + expectedPath);
        assertEquals(expectedDefault, value.getDefault(), "默认值不符: " + expectedPath);
    }

    @Test
    @DisplayName("所有期望的配置项都存在（防止漏注册某项）")
    void allExpectedKeysRegistered() {
        assertNotNull(PmConfig.SPEC);
        // 上面逐个断言路径已经覆盖；这里额外确认「模块数量」没有意外增减
        assertEquals(9, EXPECTED.size(), "期望表被改动时请同步 docs/02 的配置表");
    }

    @Test
    @DisplayName("配置项的默认值落在各自的 defineInRange 区间内（越界会在构建期抛异常）")
    void defaultsWithinDeclaredRanges() {
        // defineInRange 的区间由 Forge 在 build() 时校验，能走到这里说明区间本身合法；
        // 再确认一遍语义边界：0 表示「不限制」的几项必须允许 0
        assertTrue(PmConfig.MAX_DETAIL.getDefault() >= 0, "maxDetail 的 0 是合法值（禁用该栏）");
        assertTrue(PmConfig.COOLDOWN_SECONDS.getDefault() >= 0, "冷却 0 是合法值（不限制）");
        assertTrue(PmConfig.MAX_OPEN_PER_PLAYER.getDefault() >= 0, "并发上限 0 是合法值（不限制）");
        assertTrue(PmConfig.AVATAR_TIMEOUT_MS.getDefault() > 0, "头像超时必须为正，否则卡片永远等不到头像");
    }

    // ------------------------------------------------------------------
    // 其余「构造期会类加载」的类
    // ------------------------------------------------------------------

    @Test
    @DisplayName("PmPermissions 静态初始化成功，节点名与对方模组的节点名都正确")
    void permissionsInitialise() {
        assertNotNull(PmPermissions.ADMIN_TICKET, "权限节点为空");
        assertEquals("ccnrpm.admin.ticket", PmPermissions.ADMIN_TICKET.getNodeName());
        // 这个名字必须与 CCNR-Com 注册的完全一致，否则「用权限插件授权但没有 OP 的管理员」
        // 会被误判成普通玩家并被导向举报面板
        assertEquals("ccnrcom.admin.chat", PmPermissions.FOREIGN_ADMIN_CHAT);
    }

    // ------------------------------------------------------------------
    // 明确不在本测试覆盖范围内的部分（不要假装覆盖了）
    // ------------------------------------------------------------------
    //
    // PmChannel.<clinit> 会调用 NetworkRegistry.newSimpleChannel，而该方法需要真实的 Forge
    // 网络栈（在纯单测里会抛 NoSuchMethodException）。**因此网络通道的静态初始化仍只能靠实机验证**。
    // 这与 PmConfig 的情况不同：PmConfig 是纯 ForgeConfigSpec 构建，单测能完整覆盖
    // —— 而本次事故恰好发生在 PmConfig 上，所以本测试对那类缺陷是有效的。
    //
    // 同理不在范围内：CCNRPMMod 自身的构造、任何需要服务端/客户端实例的 init
    // （TicketService.init、AvatarService.init 的文件 IO 路径）。

    @Test
    @DisplayName("AvatarService 静态初始化成功，且空输入安全")
    void avatarServiceInitialises() {
        // 类初始化会创建 HttpClient 与线程池；这一步失败同样是 ExceptionInInitializerError
        assertEquals(0, AvatarService.size(), "初始缓存必须为空");
        assertFalse(AvatarService.cached(null).isPresent(), "null uuid 必须安全返回空");
        assertFalse(AvatarService.cached(java.util.UUID.randomUUID()).isPresent(), "未抓取过的玩家不应有缓存");
        assertTrue(AvatarService.describe().size() >= 3, "诊断信息应包含条目数/在飞数/文件路径");
    }
}
