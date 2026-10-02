/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.pm.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 对局快照的解析门禁。
 *
 * <p>这份数据来自**外部模组**（服务端反射读 CCNR-RP 后拼成 JSON），因此「坏输入」是常态：
 * 对方改了字段名、某个键没了、类型变了、事件流里混进 null。解析规则必须一律降级，
 * 绝不能让对局页签因为一条坏数据整个炸掉——而这是一个**每秒**都在跑的路径。
 *
 * <p>另一类必须钉住的是**派生读数**：{@code phaseTimerMs - (now - receivedAt)} 的时钟换算、
 * 事件状态到「能不能触发/结束」的判定。它们在界面里表现为「倒计时不动」或「按钮永远灰着」，
 * 靠眼睛看是发现不了的。
 */
class MatchSnapshotTest {

    /** 构造一份贴近真实载荷的 JSON（字段名与 {@code RpBridge.matchSnapshot()} 一致）。 */
    private static String payload(String matchExtra) {
        return "{\"available\":true,"
                + "\"match\":{\"serverNowMs\":1000,\"running\":true,"
                + "\"mode\":{\"id\":\"hardcore\",\"active\":true,\"name\":\"硬核生存\",\"desc\":\"\",\"icon\":\"\"},"
                + "\"phase\":{\"id\":\"dusk\",\"name\":\"黄昏\",\"index\":2,\"total\":7,\"conditionDriven\":false},"
                + "\"phaseTimerMs\":60000,"
                + "\"events\":[{\"id\":\"air_raid\",\"name\":\"空袭警报\"}]"
                + matchExtra
                + "},"
                + "\"phases\":[{\"id\":\"dawn\",\"name\":\"黎明\"},{\"id\":\"dusk\",\"name\":\"黄昏\"}],"
                + "\"events\":["
                + "{\"id\":\"air_raid\",\"name\":\"空袭警报\",\"state\":\"RUNNING\",\"enabled\":true},"
                + "{\"id\":\"supply\",\"name\":\"物资投放\",\"state\":\"SCHEDULED\",\"enabled\":true},"
                + "{\"id\":\"retired\",\"name\":\"已停用\",\"state\":\"SCHEDULED\",\"enabled\":false}"
                + "],"
                + "\"log\":[{\"at\":1700000000000,\"kind\":\"EVENT_KIND_CHARACTER_KILL\",\"text\":\"A 击杀 B\"}]"
                + "}";
    }

    private static MatchSnapshot parse(String json) {
        Optional<MatchSnapshot> s = MatchSnapshot.fromJson(json);
        assertTrue(s.isPresent(), "应当解析成功: " + json);
        return s.get();
    }

    // ------------------------------------------------------------------
    // 解析与降级
    // ------------------------------------------------------------------

    @Test
    @DisplayName("完整载荷：对局状态、幕表、事件表、事件流全部到位")
    void parsesFullPayload() {
        MatchSnapshot s = parse(payload(""));
        assertTrue(s.available());
        assertTrue(s.running());
        assertEquals("硬核生存", s.modeName());
        assertEquals("dusk", s.phaseId());
        assertEquals("黄昏", s.phaseName());
        assertEquals(2, s.phaseIndex());
        assertEquals(7, s.phaseTotal());
        assertEquals(60000L, s.phaseTimerMs());
        assertEquals(List.of("空袭警报"), s.activeEventNames());
        assertEquals(2, s.phases().size());
        assertEquals("黎明", s.phases().get(0).name());
        assertEquals(3, s.events().size());
        assertEquals(1, s.log().size());
        assertEquals("CHARACTER_KILL", s.log().get(0).shortKind());
        assertEquals("A 击杀 B", s.log().get(0).text());
    }

    @Test
    @DisplayName("available=false 是一份正常载荷（对方没装 RP），不是错误")
    void absentIsNormalPayload() {
        MatchSnapshot s = parse("{\"available\":false}");
        assertFalse(s.available());
        assertFalse(s.running());
        assertEquals("", s.modeName());
        assertEquals(-1, s.phaseIndex());
        assertTrue(s.phases().isEmpty());
        assertTrue(s.events().isEmpty());
        assertTrue(s.log().isEmpty());
        assertEquals(-1L, s.phaseTimerMs());
    }

    @Test
    @DisplayName("坏输入返回空（调用方保留上一份快照，不把正在倒计时的面板变空）")
    void badInputIsEmpty() {
        assertTrue(MatchSnapshot.fromJson(null).isEmpty());
        assertTrue(MatchSnapshot.fromJson("").isEmpty());
        assertTrue(MatchSnapshot.fromJson("不是 JSON").isEmpty());
        assertTrue(MatchSnapshot.fromJson("[]").isEmpty(), "根节点必须是对象");
        assertTrue(MatchSnapshot.fromJson("\"x\"").isEmpty());
    }

    @Test
    @DisplayName("缺 match 段落不炸：所有读数回落到安全默认值")
    void missingMatchSectionDegrades() {
        MatchSnapshot s = parse("{\"available\":true}");
        assertTrue(s.available());
        assertFalse(s.running());
        assertEquals("", s.modeName());
        assertEquals(-1L, s.phaseTimerMs());
        assertEquals(-1L, s.seqTimerMs());
        assertTrue(s.activeEventNames().isEmpty());
        assertNotNull(s.match());
    }

    @Test
    @DisplayName("幕/事件/事件流里的坏元素跳过而不是整段丢弃（缺 id 的条目无法被操作）")
    void badElementsAreSkipped() {
        MatchSnapshot s = parse("{\"available\":true,\"match\":{},"
                + "\"phases\":[{\"name\":\"没有 id\"},{\"id\":\"ok\",\"name\":\"好的\"},7,null],"
                + "\"events\":[{\"id\":\"\",\"name\":\"空 id\"},{\"id\":\"e1\"},null],"
                + "\"log\":[{\"text\":\"只有正文\"},{\"kind\":\"K\"},{\"at\":1,\"kind\":\"K\",\"text\":\"T\"}]}");
        assertEquals(1, s.phases().size());
        assertEquals("ok", s.phases().get(0).id());
        assertEquals(1, s.events().size());
        // 没有名字的事件回退 id；没有 state 的回退 SCHEDULED；没有 enabled 的回退 false（fail-closed）
        assertEquals("e1", s.events().get(0).name());
        assertEquals("SCHEDULED", s.events().get(0).state());
        assertFalse(s.events().get(0).enabled());
        assertEquals(2, s.log().size(), "正文与种类都没有的行丢掉");
    }

    @Test
    @DisplayName("数组字段不是数组时当作空（对方改了结构不该让页签炸掉）")
    void wrongTypeForListsDegrades() {
        MatchSnapshot s =
                parse("{\"available\":true,\"match\":{}," + "\"phases\":\"not-a-list\",\"events\":42,\"log\":{}}");
        assertTrue(s.phases().isEmpty());
        assertTrue(s.events().isEmpty());
        assertTrue(s.log().isEmpty());
    }

    @Test
    @DisplayName("特殊字符（引号、换行、中文）不影响解析")
    void specialCharactersSurvive() {
        MatchSnapshot s = parse("{\"available\":true,\"match\":{},\"log\":"
                + "[{\"at\":1,\"kind\":\"K\",\"text\":\"带\\\"引号\\\"与\\n换行 的中文\"}]}");
        assertEquals(1, s.log().size());
        assertEquals("带\"引号\"与\n换行 的中文", s.log().get(0).text());
    }

    // ------------------------------------------------------------------
    // 派生读数
    // ------------------------------------------------------------------

    @Test
    @DisplayName("倒计时按本地时钟递减：剩余 = 载荷值 - (现在 - 收到时刻)")
    void timerCountsDownLocally() {
        MatchSnapshot s = parse(payload(""));
        long received = s.receivedAtMs();
        assertEquals(60000L, s.phaseRemainMs(received));
        assertEquals(45000L, s.phaseRemainMs(received + 15_000L));
        // 过冲到负数必须夹到 0，否则界面会显示 "-00:01"
        assertEquals(0L, s.phaseRemainMs(received + 90_000L));
    }

    @Test
    @DisplayName("本地时钟倒退（收到时刻在未来）不会让剩余时间变多")
    void timerClampsNegativeElapsed() {
        MatchSnapshot s = parse(payload(""));
        assertEquals(60000L, s.phaseRemainMs(s.receivedAtMs() - 10_000L));
    }

    @Test
    @DisplayName("没有倒计时的幕（条件驱动）返回 -1，界面据此改显示「条件驱动」而不是 00:00")
    void missingTimerIsMinusOne() {
        MatchSnapshot s = parse("{\"available\":true,\"match\":{\"phase\":{\"id\":\"p\",\"conditionDriven\":true}}}");
        assertEquals(-1L, s.phaseRemainMs(System.currentTimeMillis()));
        assertTrue(s.phaseConditionDriven());
    }

    @Test
    @DisplayName("行为序列计时优先于幕倒计时（两者都在时才有多余信息）")
    void sequenceTimerIsSeparate() {
        MatchSnapshot s = parse(payload(",\"seqTimerMs\":30000"));
        long received = s.receivedAtMs();
        assertEquals(30000L, s.seqRemainMs(received));
        assertEquals(60000L, s.phaseRemainMs(received));
        assertEquals(20000L, s.seqRemainMs(received + 10_000L));
    }

    @Test
    @DisplayName("事件状态决定按钮语义：RUNNING 只能结束，SCHEDULED+enabled 才能触发")
    void eventStateDrivesButtons() {
        MatchSnapshot s = parse(payload(""));
        MatchSnapshot.Event running = s.events().get(0);
        MatchSnapshot.Event scheduled = s.events().get(1);
        MatchSnapshot.Event disabled = s.events().get(2);

        assertTrue(running.running());
        assertTrue(running.endable());
        assertFalse(running.triggerable());

        assertFalse(scheduled.running());
        assertFalse(scheduled.endable());
        assertTrue(scheduled.triggerable());

        assertFalse(disabled.triggerable(), "未启用的事件对方会回绝，界面不该给它一个能点的按钮");
        assertFalse(disabled.endable());
    }

    @Test
    @DisplayName("事件状态是对方枚举的名字原样透传（不做二次映射）")
    void eventStateIsRawEnumName() {
        MatchSnapshot s = parse(payload(""));
        assertEquals("RUNNING", s.events().get(0).state());
        assertEquals("SCHEDULED", s.events().get(1).state());
    }
}
