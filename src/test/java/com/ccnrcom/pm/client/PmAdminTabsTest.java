/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.pm.client;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 管理面板页签注册门禁。
 *
 * <p>**为什么需要它**：页签 id 在三个地方各出现一次——{@code PmAdminScreen.TABS} 的注册表、
 * 构造器里的 {@code case "<id>"} 分支、语言键 {@code ccnr_pm.gui.panel.tab.<id>}。
 * 三处只要漏一处，症状分别是「页签按钮点了没内容」与「按钮上显示原始键名（红字）」，
 * 而这**只有把面板打开才看得见**——本项目禁止起服务验证（见 AGENTS 硬性禁令），
 * 所以必须在编译期把这三者钉在一起。
 *
 * <p>语言包那一条是 {@code LangFileTest.everyReferencedKeyExists} 的盲区：页签的键是
 * **拼接**出来的（{@code "ccnr_pm.gui.panel.tab." + id}），静态扫描只看得见带点的前缀、
 * 看不见具体 id，所以它天然管不到「新增了一个页签却忘了翻译」。
 */
class PmAdminTabsTest {

    /** {@code private static final List<String> TABS = List.of("a", "b");} */
    private static final Pattern TABS_DECL = Pattern.compile("TABS\\s*=\\s*List\\.of\\(([^)]*)\\)");

    private static final Pattern TABS_ITEM = Pattern.compile("\"([a-z_]+)\"");

    /** 构造器里的 {@code case "tickets" ->}。 */
    private static final Pattern CASE_BRANCH = Pattern.compile("case\\s+\"([a-z_]+)\"\\s*->");

    private static Path projectRoot() {
        Path dir = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        for (int i = 0; i < 6 && dir != null; i++) {
            if (Files.isRegularFile(dir.resolve("gradle.properties")) && Files.isDirectory(dir.resolve("src"))) {
                return dir;
            }
            dir = dir.getParent();
        }
        return fail("找不到项目根目录：user.dir=" + System.getProperty("user.dir"));
    }

    private static String read(String relative) {
        try {
            return Files.readString(projectRoot().resolve(relative), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return fail("读取失败: " + relative + " —— " + e);
        }
    }

    private static Set<String> langKeys(String lang) {
        String path = "/assets/ccnr_pm/lang/" + lang + ".json";
        try (InputStream in = PmAdminTabsTest.class.getResourceAsStream(path)) {
            if (in == null) return fail("语言文件缺失: " + path);
            try (InputStreamReader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                com.google.gson.JsonObject o =
                        com.ccnrcom.pm.util.JsonUtil.GSON.fromJson(r, com.google.gson.JsonObject.class);
                return new LinkedHashSet<>(o.keySet());
            }
        } catch (IOException e) {
            return fail("读取语言文件失败: " + path + " —— " + e);
        }
    }

    /** 注册表里的页签 id。 */
    private static Set<String> registeredTabs() {
        String src = read("src/main/java/com/ccnrcom/pm/client/PmAdminScreen.java");
        Matcher m = TABS_DECL.matcher(src);
        if (!m.find()) return fail("没有解析到 TABS 声明，门禁失效");
        Set<String> ids = new LinkedHashSet<>();
        Matcher item = TABS_ITEM.matcher(m.group(1));
        while (item.find()) ids.add(item.group(1));
        assertFalse(ids.isEmpty(), "TABS 是空的，门禁失效");
        return ids;
    }

    @Test
    @DisplayName("每个注册的页签都有对应的实现分支（漏了＝页签按钮点了没内容）")
    void everyTabHasAnImplementation() {
        String src = read("src/main/java/com/ccnrcom/pm/client/PmAdminScreen.java");
        Set<String> branches = new LinkedHashSet<>();
        Matcher m = CASE_BRANCH.matcher(src);
        while (m.find()) branches.add(m.group(1));

        Set<String> missing = new TreeSet<>(registeredTabs());
        missing.removeAll(branches);
        assertTrue(missing.isEmpty(), "以下页签在 TABS 里注册了但构造器里没有实现分支: " + missing);

        // 反向：实现了却没注册的页签永远不会被 new 出来（死代码）
        Set<String> unregistered = new TreeSet<>(branches);
        unregistered.removeAll(registeredTabs());
        assertTrue(unregistered.isEmpty(), "以下实现分支没有对应的 TABS 条目: " + unregistered);
    }

    @Test
    @DisplayName("每个页签都有中英双语的页签名（缺了会在按钮上显示原始键名）")
    void everyTabHasBothTranslations() {
        for (String lang : new String[] {"zh_cn", "en_us"}) {
            Set<String> keys = langKeys(lang);
            Set<String> missing = new TreeSet<>();
            for (String id : registeredTabs()) {
                if (!keys.contains("ccnr_pm.gui.panel.tab." + id)) missing.add(id);
            }
            assertTrue(missing.isEmpty(), lang + " 缺少页签名（ccnr_pm.gui.panel.tab.<id>）: " + missing);
        }
    }

    @Test
    @DisplayName("页签实现类都真的实现了 PmTab，且 id() 与注册表一致")
    void implementationsAgreeOnId() {
        for (String id : registeredTabs()) {
            // 只按约定找类：id 首字母大写 + Tab 后缀（tickets -> PmTicketsTab）
            String cls = "Pm" + Character.toUpperCase(id.charAt(0)) + id.substring(1) + "Tab";
            String fq = "com.ccnrcom.pm.client.tab." + cls;
            Class<?> c;
            try {
                c = Class.forName(fq);
            } catch (ClassNotFoundException e) {
                fail("找不到页签实现类 " + fq + "（注册表里有 " + id + "）");
                return;
            }
            assertTrue(com.ccnrcom.pm.client.ui.PmTab.class.isAssignableFrom(c), cls + " 没有实现 PmTab");
            assertTrue(
                    read("src/main/java/com/ccnrcom/pm/client/tab/" + cls + ".java")
                            .contains("return \"" + id + "\";"),
                    cls + " 的 id() 没有返回注册表里的 \"" + id + "\"");
        }
    }
}
