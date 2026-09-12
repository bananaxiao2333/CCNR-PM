/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.pm.client.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.function.ToIntFunction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 文本换行/裁剪的纯逻辑测试。
 *
 * <p>宽度函数用「每字符 6 像素」的假实现，这样断行规则可以在没有客户端环境的 CI 里被精确断言。
 */
class PmTextLayoutTest {

    /** 每个字符 6 像素（含中文，简化处理）。 */
    private static final ToIntFunction<String> SIX = s -> 6 * s.length();

    @Test
    @DisplayName("按字符贪心断行，不依赖空格（中文句子的关键）")
    void wrapsByCharacter() {
        List<String> lines = PmTextLayout.wrapText("abcdef", 12, SIX);
        assertEquals(List.of("ab", "cd", "ef"), lines);
    }

    @Test
    @DisplayName("中文长句按宽度断开，不会整行溢出")
    void wrapsChineseWithoutSpaces() {
        // 6 个汉字，每字 6px；每行最多容纳 12px = 2 个字
        List<String> lines = PmTextLayout.wrapText("你好世界再见", 12, SIX);
        assertEquals(3, lines.size());
        for (String line : lines) {
            assertTrue(SIX.applyAsInt(line) <= 12, "每行都不应超过宽度上限: " + line);
        }
        assertEquals("你好世界再见", String.join("", lines), "断行不得丢字");
    }

    @Test
    @DisplayName("显式换行符强制换行")
    void honoursExplicitNewline() {
        assertEquals(List.of("a", "b"), PmTextLayout.wrapText("a\nb", 100, SIX));
        assertEquals(List.of("a", "", "b"), PmTextLayout.wrapText("a\n\nb", 100, SIX));
    }

    @Test
    @DisplayName("空串返回一行空串，便于统一按行绘制")
    void emptyTextYieldsOneLine() {
        assertEquals(List.of(""), PmTextLayout.wrapText("", 100, SIX));
        assertEquals(List.of(""), PmTextLayout.wrapText(null, 100, SIX));
    }

    @Test
    @DisplayName("裁剪在放得下时原样返回")
    void clipKeepsShortText() {
        assertEquals("abc", PmTextLayout.clip("abc", 100, SIX));
        assertEquals("", PmTextLayout.clip("", 100, SIX));
        assertEquals("", PmTextLayout.clip(null, 100, SIX));
    }

    @Test
    @DisplayName("裁剪追加省略号且不超过宽度上限")
    void clipAddsEllipsis() {
        String clipped = PmTextLayout.clip("abcdef", 20, SIX);
        assertEquals("ab" + PmTextLayout.ELLIPSIS, clipped);
        assertTrue(SIX.applyAsInt(clipped) <= 20);
    }

    @Test
    @DisplayName("连一个字符都放不下时返回首字符，而不是光秃秃的省略号")
    void clipFallsBackToFirstChar() {
        String clipped = PmTextLayout.clip("abcdef", 8, SIX);
        assertEquals("a", clipped);
    }
}
