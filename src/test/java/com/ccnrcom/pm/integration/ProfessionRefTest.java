/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.pm.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * CCNR-RP 职业列表的编解码门禁。
 *
 * <p>这份数据来自**外部模组**（反射读出的职业表），因此「坏输入」不是假设而是常态：
 * 对方改了结构、名字里带引号、列表里混进 null。解析必须一律降级为空列表，
 * 绝不能让角色选择弹窗因为一条坏数据整个炸掉。
 */
class ProfessionRefTest {

    @Test
    @DisplayName("往返一致：编码再解码得到同样的 id/name")
    void roundTrip() {
        List<ProfessionRef> roles = List.of(new ProfessionRef("rifleman", "步枪兵"), new ProfessionRef("medic", "医疗兵"));
        List<ProfessionRef> back = ProfessionRef.fromJson(ProfessionRef.toJson(roles));
        assertEquals(roles, back);
    }

    @Test
    @DisplayName("id 为空或 null 的条目不上包（选了也无法部署）")
    void blankIdsAreDropped() {
        String json = ProfessionRef.toJson(List.of(
                new ProfessionRef("", "无名"), new ProfessionRef(null, "空 id"), new ProfessionRef("medic", "医疗兵")));
        List<ProfessionRef> back = ProfessionRef.fromJson(json);
        assertEquals(1, back.size());
        assertEquals("medic", back.get(0).id());
    }

    @Test
    @DisplayName("没有显示名时回退为 id（比在弹窗里显示一串空白有用）")
    void missingNameFallsBackToId() {
        ProfessionRef r = new ProfessionRef("rifleman", "   ");
        assertEquals("rifleman", r.name());
        List<ProfessionRef> back = ProfessionRef.fromJson(ProfessionRef.toJson(List.of(r)));
        assertEquals("rifleman", back.get(0).name());
    }

    @Test
    @DisplayName("坏输入降级为空列表（外部数据坏了不该让弹窗炸掉）")
    void badInputDegradesToEmpty() {
        assertTrue(ProfessionRef.fromJson(null).isEmpty());
        assertTrue(ProfessionRef.fromJson("").isEmpty());
        assertTrue(ProfessionRef.fromJson("不是 JSON").isEmpty());
        assertTrue(ProfessionRef.fromJson("{\"roles\":[]}").isEmpty(), "对象不是数组");
        assertTrue(ProfessionRef.fromJson("[1,2,\"x\",null]").isEmpty(), "元素不是对象");
        assertTrue(ProfessionRef.fromJson("[{\"name\":\"只有名字\"}]").isEmpty(), "缺 id 的条目丢掉");
    }

    @Test
    @DisplayName("特殊字符（引号、换行、中文）不破坏 JSON")
    void specialCharactersSurvive() {
        List<ProfessionRef> roles = List.of(new ProfessionRef("a\"b", "带\"引号\"\n换行 的角色"));
        List<ProfessionRef> back = ProfessionRef.fromJson(ProfessionRef.toJson(roles));
        assertEquals(1, back.size());
        assertEquals("a\"b", back.get(0).id());
        assertEquals("带\"引号\"\n换行 的角色", back.get(0).name());
    }
}
