/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.pm.avatar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 头像缓存的「写入 → 落盘 → 重载」链路测试。
 *
 * <p>**为什么必须有这个测试**：用户实机反馈「退出重进后头像全变成默认皮肤」。
 * 根因是抓取时机（只在管理员登录时抓）而不是持久化本身，但这条链路此前**从未被验证过**——
 * 而它一旦坏掉，症状同样是「头像全没了」，且只有实机才看得见。
 * 项目禁止起服务器验证，所以这里把链路真跑一遍：写入 → 落盘 → 重新载入 → 还在。
 *
 * <p>覆盖的 MC 无关部分（{@code init}/{@code put}/{@code cached}/{@code shutdown}）不需要游戏运行时，
 * 因此可以直接单测；需要在线玩家的 {@code PlayerRef} 与 HTTP 抓取不在此范围。
 */
class AvatarServiceTest {

    private static final UUID ALICE = UUID.fromString("11111111-2222-3333-4444-555555555555");

    private static final UUID BOB = UUID.fromString("66666666-7777-8888-9999-aaaaaaaaaaaa");

    private static final String PNG =
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==";

    @AfterEach
    void tearDown() {
        // 静态缓存会跨测试残留，逐个清掉
        AvatarService.shutdown();
    }

    @Test
    @DisplayName("写入后落盘：重启（重新 init）仍能读到（症状「头像全变默认皮肤」的回归保护）")
    void survivesRestart(@TempDir Path dir) {
        AvatarService.init(dir);
        AvatarService.put(ALICE, "Alice", PNG);
        AvatarService.shutdown();

        Path file = dir.resolve("avatars.json");
        assertTrue(Files.isRegularFile(file), "关闭时必须把缓存落盘到 avatars.json");

        // 模拟「重启服务端」：清空内存后重新载入
        AvatarService.init(dir);
        assertTrue(AvatarService.has(ALICE), "重载后应当仍有该玩家的头像");
        assertEquals(PNG, AvatarService.cached(ALICE).orElseThrow(), "base64 内容必须原样保留");
        assertEquals(1, AvatarService.size());
    }

    @Test
    @DisplayName("多个玩家都能持久化，且互不覆盖")
    void multipleEntries(@TempDir Path dir) {
        AvatarService.init(dir);
        AvatarService.put(ALICE, "Alice", PNG);
        AvatarService.put(BOB, "Bob", PNG + "x");
        AvatarService.shutdown();

        AvatarService.init(dir);
        assertEquals(2, AvatarService.size());
        assertEquals(PNG, AvatarService.cached(ALICE).orElseThrow());
        assertTrue(AvatarService.cached(BOB).orElseThrow().endsWith("x"));
    }

    @Test
    @DisplayName("新头像覆盖旧的（用户要求的「全局共享、新的覆盖旧」）")
    void newOverwritesOld(@TempDir Path dir) {
        AvatarService.init(dir);
        AvatarService.put(ALICE, "Alice", "OLD");
        AvatarService.put(ALICE, "Alice", "NEW");
        AvatarService.shutdown();

        AvatarService.init(dir);
        assertEquals(1, AvatarService.size());
        assertEquals("NEW", AvatarService.cached(ALICE).orElseThrow());
    }

    @Test
    @DisplayName("配置文件不存在时不抛异常，只是缓存为空（首次启动）")
    void missingFileIsFine(@TempDir Path dir) {
        AvatarService.init(dir.resolve("nested"));
        assertEquals(0, AvatarService.size());
        assertFalse(AvatarService.has(ALICE));
        assertFalse(AvatarService.cached(ALICE).isPresent());
    }

    @Test
    @DisplayName("文件损坏时降级为空缓存而不是崩掉（头像丢了可重抓，服务不能再起不来）")
    void corruptedFileDegrades(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("avatars.json"), "{ this is not json");
        AvatarService.init(dir);
        assertEquals(0, AvatarService.size());

        // 损坏之后仍能正常写入（JsonUtil 会把坏文件备份成 .bak）
        AvatarService.put(ALICE, "Alice", PNG);
        AvatarService.shutdown();
        AvatarService.init(dir);
        assertEquals(1, AvatarService.size());
    }

    @Test
    @DisplayName("缓存条目损坏时跳过该条，其余照常载入")
    void partiallyCorruptedEntriesSkipped(@TempDir Path dir) throws Exception {
        Files.writeString(
                dir.resolve("avatars.json"),
                "{\"version\":1,\"avatars\":{"
                        + "\"not-a-uuid\":{\"name\":\"X\",\"png\":\"" + PNG + "\"},"
                        + "\"" + ALICE + "\":{\"name\":\"Alice\",\"png\":\"" + PNG + "\"},"
                        + "\"" + BOB + "\":{\"name\":\"Bob\"}"
                        + "}}");
        AvatarService.init(dir);
        assertEquals(1, AvatarService.size(), "坏键与缺 png 的条目应被跳过，合法的保留");
        assertTrue(AvatarService.has(ALICE));
    }

    @Test
    @DisplayName("空输入安全（缺 uuid 不得抛异常）")
    void nullSafety() {
        AvatarService.init(null);
        assertEquals(0, AvatarService.size());
        assertFalse(AvatarService.cached(null).isPresent());
        assertFalse(AvatarService.has(null));
        AvatarService.forgetNegative(null); // 不应抛异常
    }

    @Test
    @DisplayName("forgetNegative 让抓取失败的玩家下次登录能重试（否则一次抖动＝整局没头像）")
    void forgetNegativeAllowsRetry() {
        AvatarService.init(null);
        // 抓取失败会把 uuid 记进 NEGATIVE，之后 prefetch 直接跳过；
        // forgetNegative 是唯一的解除入口（在玩家登录时调用）
        AvatarService.forgetNegative(ALICE);
        assertFalse(AvatarService.has(ALICE));
        // 解除后再次 prefetch 不会因为「已知抓不到」被跳过——此处只验证调用安全与状态不变
        AvatarService.forgetNegative(ALICE);
    }

    @Test
    @DisplayName("诊断信息包含条目数与文件路径（排查用）")
    void describeIsUseful(@TempDir Path dir) {
        AvatarService.init(dir);
        AvatarService.put(ALICE, "Alice", PNG);
        var lines = AvatarService.describe();
        assertTrue(lines.size() >= 3);
        assertTrue(lines.stream().anyMatch(l -> l.startsWith("entries=1/")), "应报告条目数与上限，实际: " + lines);
        assertTrue(lines.stream().anyMatch(l -> l.contains("avatars.json")), "应报告文件路径，实际: " + lines);
    }
}
