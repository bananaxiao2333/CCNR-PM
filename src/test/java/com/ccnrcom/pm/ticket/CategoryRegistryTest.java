/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.pm.ticket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 举报类别注册表测试：数据驱动、容错、以及「不因一次坏重载清空面板」的保护。 */
class CategoryRegistryTest {

    @Test
    @DisplayName("内置默认类别齐全且全部启用")
    void defaultsAreComplete() {
        CategoryRegistry reg = new CategoryRegistry(CategoryRegistry.defaults());
        assertEquals(CategoryRegistry.DEFAULT_IDS.size(), reg.enabled().size());
        for (String id : CategoryRegistry.DEFAULT_IDS) {
            assertTrue(reg.isSelectable(id), "默认类别应可选: " + id);
        }
    }

    @Test
    @DisplayName("按 order 排序，面板顺序可预期")
    void sortsByOrder() {
        CategoryRegistry reg = new CategoryRegistry(
                List.of(new TicketCategory("b", null, 20, true), new TicketCategory("a", null, 10, true)));
        assertEquals(
                List.of("a", "b"), reg.all().stream().map(TicketCategory::id).toList());
    }

    @Test
    @DisplayName("停用的类别不再可选，但仍保留在表里以便历史工单显示")
    void disabledCategoryNotSelectable() {
        CategoryRegistry reg = new CategoryRegistry(
                List.of(new TicketCategory("on", null, 1, true), new TicketCategory("off", null, 2, false)));
        assertTrue(reg.isSelectable("on"));
        assertFalse(reg.isSelectable("off"));
        assertTrue(reg.byId("off").isPresent(), "停用类别仍应能查到（历史工单要显示名字）");
        assertEquals(1, reg.enabled().size());
    }

    @Test
    @DisplayName("解析配置：缺字段用默认值补齐")
    void parseToleratesMissingFields() {
        JsonObject root = new JsonObject();
        JsonArray arr = new JsonArray();
        JsonObject a = new JsonObject();
        a.addProperty("id", "custom");
        arr.add(a);
        root.add("categories", arr);

        CategoryRegistry reg = CategoryRegistry.parse(root);
        assertEquals(1, reg.all().size());
        assertTrue(reg.isSelectable("custom"));
        assertEquals(10, reg.all().get(0).order(), "未给 order 时按 10 递增补齐");
    }

    @Test
    @DisplayName("解析配置：管理员自定义 name 直接生效")
    void parseKeepsCustomName() {
        JsonObject root = new JsonObject();
        JsonArray arr = new JsonArray();
        JsonObject a = new JsonObject();
        a.addProperty("id", "custom");
        a.addProperty("name", "自定义类别");
        arr.add(a);
        root.add("categories", arr);

        TicketCategory c = CategoryRegistry.parse(root).all().get(0);
        assertTrue(c.hasCustomName());
        assertEquals("自定义类别", c.customName());
    }

    @Test
    @DisplayName("解析配置：重复 id 去重而不是整体失效")
    void parseDeduplicates() {
        JsonObject root = new JsonObject();
        JsonArray arr = new JsonArray();
        for (String name : List.of("first", "second")) {
            JsonObject a = new JsonObject();
            a.addProperty("id", "dup");
            a.addProperty("name", name);
            arr.add(a);
        }
        root.add("categories", arr);

        CategoryRegistry reg = CategoryRegistry.parse(root);
        assertEquals(1, reg.all().size(), "重复 id 只应保留一条");
        assertEquals("second", reg.all().get(0).customName(), "后者覆盖前者");
    }

    @Test
    @DisplayName("无有效条目时返回 null，由调用方决定回退")
    void parseReturnsNullWhenEmpty() {
        assertNull(CategoryRegistry.parse(null));
        assertNull(CategoryRegistry.parse(new JsonObject()));
        JsonObject root = new JsonObject();
        root.add("categories", new JsonArray());
        assertNull(CategoryRegistry.parse(root));
        JsonObject badId = new JsonObject();
        JsonArray arr = new JsonArray();
        JsonObject a = new JsonObject();
        a.addProperty("id", "   ");
        arr.add(a);
        badId.add("categories", arr);
        assertNull(CategoryRegistry.parse(badId));
    }

    @Test
    @DisplayName("安装空注册表被忽略，避免一次坏重载把面板清空")
    void installIgnoresEmpty() {
        CategoryRegistry before = CategoryRegistry.active();
        CategoryRegistry.install(new CategoryRegistry(List.of()));
        assertEquals(before, CategoryRegistry.active());

        CategoryRegistry next = new CategoryRegistry(List.of(new TicketCategory("only", null, 1, true)));
        CategoryRegistry.install(next);
        assertEquals(next, CategoryRegistry.active());
        // 还原，避免影响其它测试
        CategoryRegistry.install(new CategoryRegistry(CategoryRegistry.defaults()));
    }

    @Test
    @DisplayName("语言键按 id 拼接，便于语言包覆盖")
    void nameKeyUsesId() {
        TicketCategory c = new TicketCategory("chat_abuse", null, 1, true);
        assertEquals("ccnr_pm.category.chat_abuse.name", c.nameKey());
        assertEquals("ccnr_pm.category.chat_abuse.desc", c.descKey());
    }
}
