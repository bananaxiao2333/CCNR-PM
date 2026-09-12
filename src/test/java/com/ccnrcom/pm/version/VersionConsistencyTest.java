/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.pm.version;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 版本号一致性门禁（规范见 docs/04）。
 *
 * <p>为什么需要一道自动门禁：版本号散落在三处（`gradle.properties` 真源、CHANGELOG、README），
 * 手工同步必然偶尔漏一处。而漏掉的后果不是构建失败，而是**玩家装错版本却无从察觉**——
 * 这类「没人会立刻发现」的错误正是应该交给测试去守的。
 *
 * <p>本测试只校验**同步关系**，不锁死 CHANGELOG 标题的写法，
 * 因此 `## [0.1.0] - 摘要` 与 `## 0.1.0（摘要）` 都能通过（见 docs/04 §5）。
 *
 * <p>项目根目录靠从工作目录向上找 `gradle.properties` 定位；找不到时**直接失败**而不是跳过——
 * 一个静默跳过的门禁等于没有门禁。
 */
class VersionConsistencyTest {

    private static final Pattern VERSION = Pattern.compile("(\\d+)\\.(\\d+)\\.(\\d+)");

    private static Path projectRoot() {
        Path dir = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        for (int i = 0; i < 6 && dir != null; i++) {
            if (Files.isRegularFile(dir.resolve("gradle.properties")) && Files.isDirectory(dir.resolve("src"))) {
                return dir;
            }
            dir = dir.getParent();
        }
        return fail("找不到项目根目录（应含 gradle.properties 与 src/）：user.dir=" + System.getProperty("user.dir"));
    }

    private static String read(Path root, String name) {
        try {
            return Files.readString(root.resolve(name), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return fail("读取失败: " + name + " —— " + e);
        }
    }

    /** 从 gradle.properties 取出真源版本号。 */
    private static String sourceVersion(Path root) {
        for (String line : read(root, "gradle.properties").lines().toList()) {
            String t = line.trim();
            if (t.startsWith("mod_version=")) {
                return t.substring("mod_version=".length()).trim();
            }
        }
        return fail("gradle.properties 里没有 mod_version");
    }

    @Test
    @DisplayName("mod_version 是规范要求的 <主>.<功能批次>.<修订> 三段数字")
    void versionShape() {
        String v = sourceVersion(projectRoot());
        assertTrue(VERSION.matcher(v).matches(), "mod_version 必须是三段数字（形如 0.1.0），实际: " + v + "（规范见 docs/04 §1）");
    }

    @Test
    @DisplayName("CHANGELOG 顶部第一个版本号与 mod_version 一致")
    void changelogMatchesSource() {
        Path root = projectRoot();
        String expected = sourceVersion(root);

        // 取第一条「带版本号的二级标题」——中间插了非版本标题（如「未发布」）也不影响判断
        String found = null;
        for (String line : read(root, "CHANGELOG.md").split("\n", -1)) {
            if (!line.startsWith("## ")) continue;
            Matcher m = VERSION.matcher(line);
            if (m.find()) {
                found = m.group();
                break;
            }
        }
        assertTrue(found != null, "CHANGELOG 里找不到任何带版本号的标题");
        assertTrue(
                expected.equals(found),
                "CHANGELOG 顶部版本(" + found + ") 与 mod_version(" + expected
                        + ") 不一致——改了版本号就要在 CHANGELOG 顶部补条目（docs/04 §4）");
    }

    @Test
    @DisplayName("README 里同步了当前版本号")
    void readmeMentionsVersion() {
        Path root = projectRoot();
        String expected = sourceVersion(root);
        assertTrue(
                read(root, "README.md").contains(expected),
                "README 未出现当前版本号 " + expected + "——请同步「当前版本」一行（docs/04 §3）");
    }

    @Test
    @DisplayName("mods.toml 用 file.jarVersion 注入，不得手写第二个版本号")
    void modsTomlDoesNotDuplicateVersion() {
        Path root = projectRoot();
        Path modsToml = root.resolve("src/main/resources/META-INF/mods.toml");
        assertTrue(Files.isRegularFile(modsToml), "mods.toml 缺失: " + modsToml);

        String body;
        try {
            body = Files.readString(modsToml, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return; // 已由上面的断言覆盖，不会走到这里
        }

        // 模组自身的版本必须来自构建注入；手写一份迟早与真源不一致
        assertTrue(
                body.contains("version=\"${file.jarVersion}\""),
                "mods.toml 的 [[mods]] version 应为 ${file.jarVersion}（docs/04 §3）");

        String expected = sourceVersion(root);
        for (String line : body.lines().toList()) {
            String t = line.trim();
            // 依赖项的 versionRange 里出现版本号是正常的（如 forge/minecraft 的区间），只查本模组自身
            if (t.startsWith("version=") && !t.contains("${file.jarVersion}")) {
                assertTrue(!t.contains(expected), "mods.toml 里手写了当前版本号 " + expected + "，应改为 ${file.jarVersion}");
            }
        }
    }

    @Test
    @DisplayName("docs/04 版本号规范存在（规范本身也要被保住）")
    void specDocumentExists() {
        Path spec = projectRoot().resolve("docs/04-版本号规范.md");
        assertTrue(Files.isRegularFile(spec), "缺少 docs/04-版本号规范.md");

        List<String> lines;
        try {
            lines = Files.readAllLines(spec, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return;
        }
        assertTrue(lines.size() > 20, "docs/04 内容过少，规范形同虚设");
    }
}
