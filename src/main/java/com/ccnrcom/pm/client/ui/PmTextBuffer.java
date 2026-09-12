/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.pm.client.ui;

import java.util.List;
import java.util.function.ToIntFunction;

/**
 * 多行文本框的**文本模型**（纯逻辑，无 MC 依赖，可直接单测）。
 *
 * <p>为什么把模型和控件分开：光标移动、选区、换行索引映射全是「差一位」错误的温床，
 * 而这些逻辑一旦和数据绑在渲染代码里就只能靠肉眼验证。抽成纯类之后，
 * 「在软换行边界上按下移键光标应该去哪」这类问题可以被精确断言。
 *
 * <p>索引约定：{@code cursor} / {@code anchor} 都是**字符索引**，取值 {@code [0, length]}。
 * {@code cursor} 是插入点，{@code anchor} 是选区锚点；两者相等表示没有选区。
 *
 * <p>边界：代理对（emoji 等星光平面字符）按**两个 char** 处理，删除与左右移动会整对跳过，
 * 因此不会留下半个代理字符渲染成方块。但不支持把光标停在代理对中间——这在本用途下没有意义。
 */
public final class PmTextBuffer {

    private final StringBuilder text = new StringBuilder();
    private final int maxLength;
    private int cursor;
    private int anchor;

    public PmTextBuffer(int maxLength) {
        this("", maxLength);
    }

    public PmTextBuffer(String initial, int maxLength) {
        this.maxLength = Math.max(0, maxLength);
        setTextInternal(initial == null ? "" : initial);
        this.cursor = text.length();
        this.anchor = this.cursor;
    }

    // ------------------------------------------------------------------
    // 读取
    // ------------------------------------------------------------------

    public String text() {
        return text.toString();
    }

    public int length() {
        return text.length();
    }

    public int cursor() {
        return cursor;
    }

    /** 选区锚点（与 {@link #cursor()} 相等表示没有选区）。 */
    public int anchor() {
        return anchor;
    }

    public boolean hasSelection() {
        return cursor != anchor;
    }

    public int selectionStart() {
        return Math.min(cursor, anchor);
    }

    public int selectionEnd() {
        return Math.max(cursor, anchor);
    }

    public String selectedText() {
        return hasSelection() ? text.substring(selectionStart(), selectionEnd()) : "";
    }

    public boolean isEmpty() {
        return text.length() == 0;
    }

    // ------------------------------------------------------------------
    // 写入
    // ------------------------------------------------------------------

    /** 整体替换（用于初始化与外部设值）；光标移到末尾。 */
    public final void setText(String s) {
        setTextInternal(s == null ? "" : s);
        cursor = text.length();
        anchor = cursor;
    }

    private void setTextInternal(String s) {
        String norm = normalise(s);
        text.setLength(0);
        text.append(norm.length() > maxLength ? norm.substring(0, maxLength) : norm);
    }

    /** 光标定位；{@code select=false} 时同时把锚点拉到光标（取消选区）。 */
    public void setCursor(int index, boolean select) {
        cursor = clamp(index);
        if (!select) anchor = cursor;
    }

    public void selectAll() {
        anchor = 0;
        cursor = text.length();
    }

    /** 插入文本（先删除选区）；超出长度上限的部分被截断。 */
    public void insert(String s) {
        if (s == null || s.isEmpty()) return;
        deleteSelection();
        String norm = normalise(s);
        int room = maxLength - text.length();
        if (room <= 0) return;
        if (norm.length() > room) norm = norm.substring(0, room);
        text.insert(cursor, norm);
        cursor += norm.length();
        anchor = cursor;
    }

    public void newline() {
        insert("\n");
    }

    /** 退格：有选区则删选区，否则删光标前一个字符（代理对整体删除）。 */
    public void backspace() {
        if (deleteSelection()) return;
        if (cursor <= 0) return;
        int from = prevIndex(cursor);
        text.delete(from, cursor);
        cursor = from;
        anchor = cursor;
    }

    /** Delete 键：有选区则删选区，否则删光标后一个字符。 */
    public void deleteForward() {
        if (deleteSelection()) return;
        if (cursor >= text.length()) return;
        int to = nextIndex(cursor);
        text.delete(cursor, to);
        anchor = cursor;
    }

    /** 删除选区；返回是否确实删掉了内容。 */
    public boolean deleteSelection() {
        if (!hasSelection()) return false;
        int from = selectionStart();
        int to = selectionEnd();
        text.delete(from, to);
        cursor = from;
        anchor = from;
        return true;
    }

    /**
     * 按整块替换（粘贴/剪切用）：先删选区再插入。
     *
     * <p>与 {@link #insert} 等价，单独留一个语义化入口是为了调用处的可读性。
     */
    public void replaceSelection(String s) {
        insert(s);
    }

    // ------------------------------------------------------------------
    // 移动
    // ------------------------------------------------------------------

    /**
     * 水平移动。
     *
     * <p>与常见编辑器一致：没有按 Shift 且存在选区时，光标**折叠到选区的对应一端**，
     * 而不是在选区端点外再走一格——后者会让人以为第一次按键没生效。
     *
     * @param dir -1 左移，+1 右移
     * @param select 是否按住 Shift（保持选区）
     */
    public void move(int dir, boolean select) {
        if (!select && hasSelection()) {
            cursor = dir < 0 ? selectionStart() : selectionEnd();
            anchor = cursor;
            return;
        }
        cursor = dir < 0 ? prevIndex(cursor) : nextIndex(cursor);
        if (!select) anchor = cursor;
    }

    /**
     * 垂直移动：保持「列像素位置」不横向漂移（短行上移会回到较长的行时应该回到原列）。
     *
     * @param rows 由 {@link PmTextLayout#wrapRows} 得到的行区间
     * @param dir -1 上移，+1 下移
     */
    public void moveVertical(List<PmTextLayout.Row> rows, int dir, ToIntFunction<String> width, boolean select) {
        if (rows == null || rows.isEmpty()) return;
        String s = text.toString();
        int rowIdx = PmTextLayout.rowIndex(rows, cursor);
        int target = Math.max(0, Math.min(rows.size() - 1, rowIdx + dir));
        if (target == rowIdx) {
            if (!select) anchor = cursor;
            return;
        }
        int colPx = PmTextLayout.columnWidth(s, rows.get(rowIdx), cursor, width);
        PmTextLayout.Row row = rows.get(target);
        int best = row.start();
        for (int i = row.start(); i <= row.end(); i++) {
            if (PmTextLayout.columnWidth(s, row, i, width) <= colPx) {
                best = i;
            } else {
                break;
            }
        }
        cursor = best;
        if (!select) anchor = cursor;
    }

    /** 行首 / 行尾。{@code dir=-1} 行首，{@code dir=+1} 行尾。 */
    public void moveLineEdge(List<PmTextLayout.Row> rows, int dir, boolean select) {
        if (rows == null || rows.isEmpty()) return;
        PmTextLayout.Row row = rows.get(PmTextLayout.rowIndex(rows, cursor));
        cursor = dir < 0 ? row.start() : row.end();
        if (!select) anchor = cursor;
    }

    /** 整篇文档首 / 尾。 */
    public void moveDocEdge(int dir, boolean select) {
        cursor = dir < 0 ? 0 : text.length();
        if (!select) anchor = cursor;
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    /**
     * 统一换行符：CRLF / CR 归一为 LF。
     *
     * <p>必须做这一步，否则换行符在文本里占两个 char，选区与行索引计算都会偏移一位。
     */
    private static String normalise(String s) {
        return s.replace("\r\n", "\n").replace('\r', '\n');
    }

    private int clamp(int index) {
        return Math.max(0, Math.min(index, text.length()));
    }

    /** 光标左移一格（跳过代理对的一半）。 */
    private int prevIndex(int index) {
        if (index <= 0) return 0;
        if (index >= 2
                && Character.isLowSurrogate(text.charAt(index - 1))
                && Character.isHighSurrogate(text.charAt(index - 2))) {
            return index - 2;
        }
        return index - 1;
    }

    /** 光标右移一格（跳过代理对的一半）。 */
    private int nextIndex(int index) {
        int len = text.length();
        if (index >= len) return len;
        if (index + 2 <= len
                && Character.isHighSurrogate(text.charAt(index))
                && Character.isLowSurrogate(text.charAt(index + 1))) {
            return index + 2;
        }
        return index + 1;
    }
}
