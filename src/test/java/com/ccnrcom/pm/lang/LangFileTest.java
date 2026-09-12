/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.pm.lang;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.ccnrcom.pm.ticket.CategoryRegistry;
import com.ccnrcom.pm.util.JsonUtil;
import com.google.gson.JsonObject;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 语言包一致性门禁。
 *
 * <p>四件事必须成立，否则会出现「英文客户端看到红色原始键」这类只有外国玩家才会碰到、
 * 国内测试永远发现不了的缺陷：
 * <ol>
 *   <li>zh_cn 与 en_us 的键集合**完全相同**</li>
 *   <li>没有「值等于键」的占位翻译（Minecraft 缺键时就把键原样显示，等于没翻译）</li>
 *   <li>每个内置举报类别都有名字与描述</li>
 *   <li>中英文的 {@code %s} 占位符数量一致（否则格式化会抛异常）</li>
 * </ol>
 */
class LangFileTest {

    private static final String[] LANGS = {"zh_cn", "en_us"};

    /** 面板与命令的关键文案（新增界面构件时把新键补进这里）。 */
    private static final String[] REQUIRED_KEYS = {
        "ccnr_pm.gui.title",
        "ccnr_pm.gui.field.content",
        "ccnr_pm.gui.field.targets",
        "ccnr_pm.gui.field.category",
        "ccnr_pm.gui.field.detail",
        "ccnr_pm.gui.content_hint",
        "ccnr_pm.gui.target_hint",
        "ccnr_pm.gui.detail_placeholder",
        "ccnr_pm.gui.none",
        "ccnr_pm.gui.submit",
        "ccnr_pm.gui.cancel",
        "ccnr_pm.gui.submitted",
        "ccnr_pm.report.err.not_ready",
        "ccnr_pm.report.err.too_many_targets",
        "ccnr_pm.command.help",
        "ccnr_pm.command.ticket.show.targets",
        "ccnr_pm.command.ticket.bad_id",
        "ccnr_pm.command.ticket.ambiguous",
        "ccnr_pm.gui.panel.title",
        "ccnr_pm.gui.panel.tab.tickets",
        "ccnr_pm.panel.action.claim",
        "ccnr_pm.panel.action.close",
        "ccnr_pm.panel.action.reject",
        "ccnr_pm.board.title",
        "ccnr_pm.board.claim",
        "ccnr_pm.board.ignore",
        "ccnr_pm.board.open_panel",
        "ccnr_pm.board.close",
        "ccnr_pm.board.release",
        "ccnr_pm.board.menu.copy",
        "ccnr_pm.board.menu.tp_here",
        "ccnr_pm.board.menu.tp_to",
        "ccnr_pm.board.menu.spawn",
        "ccnr_pm.board.menu.spawn_tp",
        "ccnr_pm.board.menu.observer",
        "ccnr_pm.board.role.title",
        "ccnr_pm.panel.mine",
        "key.ccnr_pm.admin_panel",
        "key.categories.ccnr_pm",
    };

    private static JsonObject load(String lang) {
        String path = "/assets/ccnr_pm/lang/" + lang + ".json";
        try (InputStream in = LangFileTest.class.getResourceAsStream(path)) {
            assertNotNull(in, "语言文件缺失: " + path);
            try (InputStreamReader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                return JsonUtil.GSON.fromJson(r, JsonObject.class);
            }
        } catch (Exception e) {
            throw new AssertionError("读取语言文件失败: " + path, e);
        }
    }

    private static Set<String> keys(JsonObject o) {
        return new LinkedHashSet<>(o.keySet());
    }

    @Test
    @DisplayName("zh_cn 与 en_us 的键集合完全一致")
    void keysetsMatch() {
        Set<String> zh = keys(load("zh_cn"));
        Set<String> en = keys(load("en_us"));

        Set<String> missingInEn = new LinkedHashSet<>(zh);
        missingInEn.removeAll(en);
        Set<String> missingInZh = new LinkedHashSet<>(en);
        missingInZh.removeAll(zh);

        assertTrue(missingInEn.isEmpty(), "en_us 缺少这些键: " + missingInEn);
        assertTrue(missingInZh.isEmpty(), "zh_cn 缺少这些键: " + missingInZh);
    }

    @Test
    @DisplayName("不存在「值等于键」的占位翻译")
    void noPlaceholderValues() {
        for (String lang : LANGS) {
            JsonObject o = load(lang);
            for (String k : o.keySet()) {
                String v = o.get(k).getAsString();
                assertFalse(v.equals(k), lang + " 的 " + k + " 是占位翻译（值等于键），客户端会显示原始键");
                assertFalse(v.isBlank(), lang + " 的 " + k + " 是空翻译");
            }
        }
    }

    @Test
    @DisplayName("每个内置举报类别都有本地化名称与描述")
    void builtinCategoriesTranslated() {
        for (String lang : LANGS) {
            JsonObject o = load(lang);
            for (String id : CategoryRegistry.DEFAULT_IDS) {
                assertTrue(o.has("ccnr_pm.category." + id + ".name"), lang + " 缺少类别名称: " + id);
                assertTrue(o.has("ccnr_pm.category." + id + ".desc"), lang + " 缺少类别描述: " + id);
            }
        }
    }

    @Test
    @DisplayName("面板与命令的关键文案都已翻译（防止新增界面漏键）")
    void requiredKeysPresent() {
        for (String lang : LANGS) {
            JsonObject o = load(lang);
            for (String key : REQUIRED_KEYS) {
                assertTrue(o.has(key), lang + " 缺少必需键: " + key);
            }
        }
    }

    @Test
    @DisplayName("每个工单状态都有本地化显示名")
    void statusesTranslated() {
        for (String lang : LANGS) {
            JsonObject o = load(lang);
            for (String id : new String[] {"open", "claimed", "closed", "rejected"}) {
                assertTrue(o.has("ccnr_pm.status." + id), lang + " 缺少状态文案: " + id);
            }
        }
    }

    /**
     * 代码里引用到的每个文案键，必须在两个语言包里都存在。
     *
     * <p>**为什么需要它**：语言包的「中英一致」检查只能保证两个文件彼此对齐，
     * 管不住「代码引用了一个两边都没有的键」——那会在界面上显示成原始键名（红字），
     * 而这只有把界面打开才看得见。项目禁止起服务验证，因此把它做成静态扫描。
     *
     * <p>这条门禁是真实事故的产物：`ccnr_pm.board.more` 曾在一次编辑中丢失，
     * 而当时的测试全绿——因为没有任何测试看过「代码引用了什么键」。
     */
    @Test
    @DisplayName("代码里引用的文案键都在语言包里（缺键会在界面上显示成原始键名）")
    void everyReferencedKeyExists() {
        java.util.Set<String> referenced = new java.util.TreeSet<>();
        java.util.regex.Pattern literal = java.util.regex.Pattern.compile(
                "\"((?:ccnr_pm\\.[a-z0-9_.]+|key\\.ccnr_pm\\.[a-z0-9_.]+|key\\.categories\\.ccnr_pm))\"");
        Path srcRoot = projectRootOrNull();
        assertFalse(srcRoot == null, "找不到 src/main/java，门禁失效");
        try (java.util.stream.Stream<Path> files = java.nio.file.Files.walk(srcRoot)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String text = java.nio.file.Files.readString(f, StandardCharsets.UTF_8);
                java.util.regex.Matcher m = literal.matcher(text);
                while (m.find()) {
                    String key = m.group(1);
                    // 以点结尾的是**动态前缀**（如 "ccnr_pm.category." + id），不是键本身；
                    // 它们指向的键由内置类别/状态/页签的枚举保证，另有测试覆盖
                    if (!key.endsWith(".")) referenced.add(key);
                }
            }
        } catch (Exception e) {
            fail("扫描源码失败: " + e);
        }
        assertFalse(referenced.isEmpty(), "没有扫描到任何文案键，门禁失效");

        for (String lang : LANGS) {
            JsonObject o = load(lang);
            java.util.Set<String> missing = new java.util.TreeSet<>();
            for (String k : referenced) {
                if (!o.has(k)) missing.add(k);
            }
            assertTrue(missing.isEmpty(), lang + " 缺少代码引用的键: " + missing);
        }
    }

    /** 项目根下的 src/main/java；找不到返回 null（由调用方判定为门禁失效）。 */
    private static Path projectRootOrNull() {
        Path dir = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        for (int i = 0; i < 6 && dir != null; i++) {
            Path candidate = dir.resolve("src/main/java");
            if (java.nio.file.Files.isDirectory(candidate)) return candidate;
            dir = dir.getParent();
        }
        return null;
    }

    @Test
    @DisplayName("格式占位符数量在中英文之间保持一致（否则会抛格式化异常）")
    void placeholderCountsMatch() {
        JsonObject zh = load("zh_cn");
        JsonObject en = load("en_us");
        for (String k : zh.keySet()) {
            if (!en.has(k)) continue;
            assertEquals(
                    countPlaceholders(zh.get(k).getAsString()),
                    countPlaceholders(en.get(k).getAsString()),
                    "占位符数量不一致: " + k);
        }
    }

    private static int countPlaceholders(String s) {
        int count = 0;
        for (int i = 0; i + 1 < s.length(); i++) {
            if (s.charAt(i) == '%' && s.charAt(i + 1) == 's') count++;
        }
        return count;
    }
}
