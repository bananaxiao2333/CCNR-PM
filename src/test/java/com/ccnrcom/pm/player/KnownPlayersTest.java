/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.pm.player;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 「本服见过的玩家」名录门禁。
 *
 * <p>它是「关联玩家是否合法」的判据来源（在线 **或** 见过面），因此三条性质必须成立：
 * 大小写不敏感（玩家大小写随手打）、**重启后仍在**（否则重启前的玩家会被判成陌生人）、
 * 以及落盘是原子的（写坏名录等于全服玩家都变成陌生人）。
 */
class KnownPlayersTest {

    private static final UUID ALICE = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID BOB = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Test
    @DisplayName("登记后即视为已知，且大小写与首尾空格不敏感")
    void rememberAndQuery() {
        KnownPlayers.init(null); // 纯内存：不触碰磁盘
        assertFalse(KnownPlayers.isKnown("Alice"), "还没登记过就不该算已知");

        KnownPlayers.remember(ALICE, "Alice");
        assertTrue(KnownPlayers.isKnown("Alice"));
        assertTrue(KnownPlayers.isKnown("alice"), "大小写不同必须算同一个人");
        assertTrue(KnownPlayers.isKnown("  ALICE  "), "手输常带空格");
        assertFalse(KnownPlayers.isKnown("Alicia"));
        assertFalse(KnownPlayers.isKnown(null));
        assertEquals(Optional.of(ALICE), KnownPlayers.uuidOf("aLiCe"));
        assertEquals(1, KnownPlayers.size());
    }

    @Test
    @DisplayName("名录里的规范名字：手输的大小写会被纠正为真实名字")
    void canonicalNameFixesCase() {
        KnownPlayers.init(null);
        KnownPlayers.remember(ALICE, "Alice");
        assertEquals("Alice", KnownPlayers.canonicalName("aLICE"));
        assertEquals("Nobody", KnownPlayers.canonicalName("Nobody"), "没见过就原样返回");
    }

    @Test
    @DisplayName("落盘 → 重载后仍然认识（否则重启即把老玩家判成陌生人）")
    void survivesReload(@TempDir Path dir) throws Exception {
        KnownPlayers.init(dir);
        KnownPlayers.remember(ALICE, "Alice");
        KnownPlayers.remember(BOB, "Bob");
        KnownPlayers.shutdown(); // 关服强制落盘

        Path file = dir.resolve("known_players.json");
        assertTrue(Files.isRegularFile(file), "关服必须落盘: " + file);

        // 模拟重启：换一个干净的内存状态再载入
        KnownPlayers.init(dir);
        KnownPlayers.shutdown(); // init 已经载入；shutdown 在这里只是收尾
        KnownPlayers.init(dir);
        assertTrue(KnownPlayers.isKnown("Alice"), "重载后必须认识 Alice");
        assertTrue(KnownPlayers.isKnown("bob"));
        assertEquals(Optional.of(BOB), KnownPlayers.uuidOf("Bob"));
        assertEquals(2, KnownPlayers.size());
    }

    @Test
    @DisplayName("坏文件/坏条目只丢该条目，不影响其它（服务不因单个文件损坏停摆）")
    void toleratesBrokenFile(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("known_players.json"), "{ 这不是 JSON");
        KnownPlayers.init(dir);
        assertEquals(0, KnownPlayers.size());
        KnownPlayers.remember(ALICE, "Alice");
        assertTrue(KnownPlayers.isKnown("Alice"), "损坏文件之后登记仍要正常工作");

        Files.writeString(
                dir.resolve("known_players.json"),
                "{\"players\":[{\"name\":\"Alice\",\"uuid\":\"不是uuid\"},{\"name\":\"Bob\",\"uuid\":\"" + BOB + "\"}]}");
        KnownPlayers.init(dir);
        assertFalse(KnownPlayers.isKnown("Alice"), "坏 uuid 的条目跳过");
        assertTrue(KnownPlayers.isKnown("Bob"), "好条目照常载入");
    }

    @Test
    @DisplayName("null / 空名字不登记（否则名录里会多出一条永远匹配不上的记录）")
    void ignoresBlankNames() {
        KnownPlayers.init(null);
        KnownPlayers.remember(null, "Alice");
        KnownPlayers.remember(ALICE, null);
        KnownPlayers.remember(ALICE, "   ");
        assertEquals(0, KnownPlayers.size());
    }

    @Test
    @DisplayName("诊断信息包含条数与文件路径（/pm status 靠它排查）")
    void describeIsInformative() {
        KnownPlayers.init(null);
        KnownPlayers.remember(ALICE, "Alice");
        String d = KnownPlayers.describe();
        assertTrue(d.contains("entries=1"), d);
        assertTrue(d.contains("file="), d);
    }
}
