/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.pm.client.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.function.ToIntFunction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 多行文本框的文本模型测试。
 *
 * <p>光标与选区的「差一位」错误无法靠肉眼发现，这里用固定宽度的假字体把边界行为钉死。
 */
class PmTextBufferTest {

    /** 每字符 6 像素：maxWidth=12 时每行恰好 2 个字符。 */
    private static final ToIntFunction<String> SIX = s -> 6 * s.length();

    private static final int TWELVE = 12;

    private static PmTextBuffer buffer(String initial) {
        return new PmTextBuffer(initial, 1000);
    }

    private static List<PmTextLayout.Row> rows(PmTextBuffer b) {
        return PmTextLayout.wrapRows(b.text(), TWELVE, SIX);
    }

    @Test
    @DisplayName("输入字符与回车换行")
    void insertAndNewline() {
        PmTextBuffer b = buffer("");
        b.insert("ab");
        b.newline();
        b.insert("cd");
        assertEquals("ab\ncd", b.text());
        assertEquals(5, b.cursor(), "光标应停在末尾");
    }

    @Test
    @DisplayName("CRLF 与 CR 统一归一为 LF（否则换行占两个 char，索引全体偏移一位）")
    void normalisesLineEndings() {
        assertEquals("a\nb", buffer("a\r\nb").text());
        assertEquals("a\nb", buffer("a\rb").text());
        assertEquals("a\nb\nc", buffer("a\r\nb\nc").text());
    }

    @Test
    @DisplayName("退格删除光标前一个字符")
    void backspaceDeletesBeforeCursor() {
        PmTextBuffer b = buffer("abc");
        b.backspace();
        assertEquals("ab", b.text());
        b.backspace();
        b.backspace();
        assertEquals("", b.text());
        b.backspace(); // 已在开头，不应抛异常
        assertEquals("", b.text());
    }

    @Test
    @DisplayName("Delete 删除光标后一个字符")
    void deleteForwardRemovesAfterCursor() {
        PmTextBuffer b = buffer("abc");
        b.setCursor(0, false);
        b.deleteForward();
        assertEquals("bc", b.text());
        assertEquals(0, b.cursor(), "光标不应移动");
    }

    @Test
    @DisplayName("有选区时退格删除的是整个选区")
    void backspaceDeletesSelection() {
        PmTextBuffer b = buffer("hello world");
        b.setCursor(0, false);
        b.setCursor(5, true);
        assertTrue(b.hasSelection());
        b.backspace();
        assertEquals(" world", b.text());
        assertFalse(b.hasSelection());
    }

    @Test
    @DisplayName("粘贴替换选区")
    void pasteReplacesSelection() {
        PmTextBuffer b = buffer("hello world");
        b.setCursor(0, false);
        b.setCursor(5, true);
        b.replaceSelection("bye");
        assertEquals("bye world", b.text());
        assertEquals(3, b.cursor());
    }

    @Test
    @DisplayName("超过长度上限的部分被截断")
    void respectsMaxLength() {
        PmTextBuffer b = new PmTextBuffer(3);
        b.insert("abcdef");
        assertEquals("abc", b.text());
        assertEquals(3, b.cursor());
        b.insert("x"); // 已满，不应再插入
        assertEquals("abc", b.text());
    }

    @Test
    @DisplayName("左右移动；无 Shift 且存在选区时折叠到选区端点")
    void horizontalMove() {
        PmTextBuffer b = buffer("abcd");
        b.setCursor(0, false);
        b.move(1, false);
        assertEquals(1, b.cursor());

        b.setCursor(1, false);
        b.setCursor(3, true);
        b.move(-1, false);
        assertEquals(1, b.cursor(), "左移应折叠到选区左端，而不是走到 0");
        b.setCursor(1, false);
        b.setCursor(3, true);
        b.move(1, false);
        assertEquals(3, b.cursor(), "右移应折叠到选区右端");
    }

    @Test
    @DisplayName("带 Shift 移动保持选区锚点")
    void shiftMoveExtendsSelection() {
        PmTextBuffer b = buffer("abcd");
        b.setCursor(1, false);
        b.move(1, true);
        b.move(1, true);
        assertEquals(3, b.cursor());
        assertEquals(1, b.anchor());
        // 锚点 1、光标 3 → 选区是 [1,3)，即索引 1..2 的 "bc"
        assertEquals("bc", b.selectedText());
    }

    @Test
    @DisplayName("上下移动保持列位置，不会横向漂移")
    void verticalMoveKeepsColumn() {
        // 每行 2 字符："ab" / "cd" / "ef" / "gh"
        PmTextBuffer b = buffer("abcdefgh");
        b.setCursor(1, false); // 第 1 行第 2 列
        b.moveVertical(rows(b), 1, SIX, false);
        assertEquals(3, b.cursor(), "下移应到第 2 行同一列（索引 3）");
        b.moveVertical(rows(b), 1, SIX, false);
        assertEquals(5, b.cursor());
        b.moveVertical(rows(b), -1, SIX, false);
        assertEquals(3, b.cursor());
    }

    @Test
    @DisplayName("下移到较短的行时贴到行尾")
    void verticalMoveClampsToShortRow() {
        PmTextBuffer b = buffer("abcd\ne");
        List<PmTextLayout.Row> r = rows(b);
        // 行： "ab" / "cd" / "e"
        b.setCursor(2, false); // "cd" 行首
        b.moveVertical(r, 1, SIX, false);
        int cursor = b.cursor();
        assertTrue(cursor >= 5 && cursor <= 6, "应落在最后一行范围内，实际 " + cursor);
    }

    @Test
    @DisplayName("行首 / 行尾与文档首尾")
    void edgeMoves() {
        PmTextBuffer b = buffer("abcdefgh");
        List<PmTextLayout.Row> r = rows(b);
        b.setCursor(3, false);
        b.moveLineEdge(r, -1, false);
        assertEquals(2, b.cursor(), "行首应为该行起始索引");
        b.moveLineEdge(r, 1, false);
        assertEquals(4, b.cursor(), "行尾应为该行结束索引");

        b.moveDocEdge(-1, false);
        assertEquals(0, b.cursor());
        b.moveDocEdge(1, false);
        assertEquals(8, b.cursor());
    }

    @Test
    @DisplayName("全选与删除选区")
    void selectAllAndDelete() {
        PmTextBuffer b = buffer("hello");
        b.selectAll();
        assertEquals("hello", b.selectedText());
        assertTrue(b.deleteSelection());
        assertEquals("", b.text());
        assertFalse(b.deleteSelection(), "没有选区时不应报告删除成功");
    }

    @Test
    @DisplayName("代理对（emoji）整体删除，不留下半个字符")
    void surrogatePairDeletedAsWhole() {
        String emoji = "\uD83D\uDE00"; // 😀
        PmTextBuffer b = buffer("a" + emoji);
        assertEquals(3, b.text().length(), "一个 emoji 占两个 char");
        b.backspace();
        assertEquals("a", b.text(), "退格应一次删掉整个 emoji");

        // 再把光标放到代理对**之前**，用 Delete 验一次同样的行为
        PmTextBuffer c = buffer("a" + emoji);
        c.setCursor(1, false);
        c.deleteForward();
        assertEquals("a", c.text(), "Delete 也应一次删掉整个 emoji");
    }

    @Test
    @DisplayName("行索引映射：软换行边界归到后一行（光标应在下一行行首）")
    void rowIndexAtWrapBoundary() {
        PmTextBuffer b = buffer("abcdef");
        List<PmTextLayout.Row> r = rows(b);
        assertEquals(0, PmTextLayout.rowIndex(r, 0));
        assertEquals(0, PmTextLayout.rowIndex(r, 1));
        assertEquals(1, PmTextLayout.rowIndex(r, 2), "行写满后光标应出现在下一行行首");
        assertEquals(2, PmTextLayout.rowIndex(r, 4));
        assertEquals(2, PmTextLayout.rowIndex(r, 6), "末尾光标仍在最后一行");
    }

    @Test
    @DisplayName("点击定位：按像素横坐标反查最近的字符索引")
    void indexAtColumn() {
        PmTextBuffer b = buffer("abcd");
        PmTextLayout.Row row = rows(b).get(0); // "ab"
        assertEquals(0, PmTextLayout.indexAtColumn(b.text(), row, 0, SIX));
        assertEquals(1, PmTextLayout.indexAtColumn(b.text(), row, 6, SIX));
        assertEquals(2, PmTextLayout.indexAtColumn(b.text(), row, 12, SIX));
        assertEquals(2, PmTextLayout.indexAtColumn(b.text(), row, 999, SIX), "超出右侧应贴到行尾");
        assertEquals(0, PmTextLayout.indexAtColumn(b.text(), row, -5, SIX), "超出左侧应贴到行首");
    }

    @Test
    @DisplayName("空文本也有一行，光标停在 0")
    void emptyBufferHasOneRow() {
        PmTextBuffer b = buffer("");
        assertEquals(1, rows(b).size());
        assertEquals(0, b.cursor());
        assertTrue(b.isEmpty());
    }

    @Test
    @DisplayName("连续换行产生空行（玩家敲的空行不能凭空消失）")
    void consecutiveNewlinesProduceEmptyRows() {
        PmTextBuffer b = buffer("a\n\nb");
        assertEquals(3, rows(b).size());
        assertEquals("", b.text().substring(2, 2));
        assertEquals(List.of("a", "", "b"), PmTextLayout.wrapText(b.text(), TWELVE, SIX));
    }
}
