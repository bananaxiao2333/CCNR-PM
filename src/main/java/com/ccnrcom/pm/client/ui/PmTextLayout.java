/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.pm.client.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.function.ToIntFunction;

/**
 * 文本换行与裁剪的**纯逻辑**（无 MC 依赖，可直接单测）。
 *
 * <p>为什么必须按**字符**断行而不是按空格：中文句子没有空格，按空格断行会把整句话当成一个「词」，
 * 结果整行溢出容器并压到按钮上——这是 CCNR-RP 踩过的实机缺陷（docs/01 §10.2 第 7 条）。
 *
 * <p>宽度由调用方以 {@code ToIntFunction<String>} 注入：真实运行时传 {@code font::width}，
 * 单测里传一个「每字符 6 像素」的假函数。这样换行算法本身可以在没有客户端环境的 CI 里验证。
 *
 * <p>**断行规则只有一份**：{@link #wrapRows} 是唯一实现，返回带字符索引的行区间；
 * {@link #wrapText} 只是把它映射成字符串。多行编辑器（{@code PmMultiLineEditBox}）需要索引来定位光标，
 * 因此走 {@code wrapRows} 而不是另写一套——两套断行规则必然漂移，症状是「画出来的行」和
 * 「光标所在的行」对不上。
 */
public final class PmTextLayout {

    /** 省略号（单字符，避免某些字体把 "..." 渲染得过宽）。 */
    public static final String ELLIPSIS = "…";

    /**
     * 一行在原文中的字符区间 {@code [start, end)}。
     *
     * <p>不存子串而是存索引：编辑器要按索引放光标、算选区，存子串就得反查，反查在重复文本上会歧义。
     */
    public record Row(int start, int end) {
        public int length() {
            return end - start;
        }
    }

    private PmTextLayout() {}

    /**
     * 贪心逐字符断行，返回每行的字符区间。
     *
     * <p>{@code '\n'} 是硬换行：该字符本身不属于任何一行（它只负责断行）。
     * 因此 {@code "a\nb"} 得到两个区间，而 {@code "a\n\nb"} 会得到一个**空行**区间——
     * 这是刻意的，空行必须能被渲染出来，否则玩家敲的空行会凭空消失。
     *
     * @param text 原文（可为 null）
     * @param maxWidth 单行宽度上限（像素）
     * @param width 单行文本的测宽函数
     * @return 至少一行；原文为空时返回一个空区间
     */
    public static List<Row> wrapRows(String text, int maxWidth, ToIntFunction<String> width) {
        List<Row> rows = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            rows.add(new Row(0, 0));
            return rows;
        }
        int rowStart = 0;
        int curLen = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\n') {
                rows.add(new Row(rowStart, i));
                rowStart = i + 1;
                curLen = 0;
                continue;
            }
            // 单字符也超宽时不丢字：宁可这一行溢出，也不要静默吞掉玩家写的内容
            if (curLen > 0 && width.applyAsInt(text.substring(rowStart, i) + c) > maxWidth) {
                rows.add(new Row(rowStart, i));
                rowStart = i;
            }
            curLen++;
        }
        rows.add(new Row(rowStart, text.length()));
        return rows;
    }

    /**
     * 贪心逐字符断行，返回每行文本。
     *
     * @return 至少一行；原文为空时返回含一个空串的列表（便于调用方统一按行绘制）
     */
    public static List<String> wrapText(String text, int maxWidth, ToIntFunction<String> width) {
        List<Row> rows = wrapRows(text, maxWidth, width);
        List<String> lines = new ArrayList<>(rows.size());
        for (Row r : rows) {
            lines.add(text == null ? "" : text.substring(r.start(), Math.min(r.end(), text.length())));
        }
        return lines;
    }

    /**
     * 按像素裁剪并追加省略号。
     *
     * <p>连一个字符都放不下时返回首字符而不是光秃秃的省略号——后者会让列表行看起来是空的。
     */
    public static String clip(String s, int maxWidth, ToIntFunction<String> width) {
        if (s == null || s.isEmpty()) return "";
        if (maxWidth <= 0) return "";
        if (width.applyAsInt(s) <= maxWidth) return s;
        int ellWidth = width.applyAsInt(ELLIPSIS);
        StringBuilder sb = new StringBuilder();
        int w = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            int cw = width.applyAsInt(String.valueOf(c));
            if (w + cw + ellWidth > maxWidth) break;
            sb.append(c);
            w += cw;
        }
        if (sb.length() == 0) return String.valueOf(s.charAt(0));
        return sb.append(ELLIPSIS).toString();
    }

    // ------------------------------------------------------------------
    // 行 / 字符索引互查（多行编辑器与命中判定共用）
    // ------------------------------------------------------------------

    /**
     * 字符索引落在第几行。
     *
     * <p>取「起始位置不大于 {@code index} 的最后一行」，因此换行符之后的索引与软换行边界上的索引
     * 都会归到**后一行**——这正符合直觉：行写满了，光标应该出现在下一行的行首。
     */
    public static int rowIndex(List<Row> rows, int index) {
        if (rows == null || rows.isEmpty()) return 0;
        int found = 0;
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).start() <= index) found = i;
            else break;
        }
        return found;
    }

    /** 行内自 {@code row.start()} 到 {@code index} 的像素宽度。 */
    public static int columnWidth(String text, Row row, int index, ToIntFunction<String> width) {
        if (text == null || row == null) return 0;
        int from = Math.max(0, Math.min(row.start(), text.length()));
        int to = Math.max(from, Math.min(index, Math.min(row.end(), text.length())));
        return width.applyAsInt(text.substring(from, to));
    }

    /** 给定行内横坐标，反查最接近的字符索引（点击定位光标用）。 */
    public static int indexAtColumn(String text, Row row, int x, ToIntFunction<String> width) {
        if (text == null || row == null) return 0;
        int end = Math.min(row.end(), text.length());
        int best = row.start();
        int bestDiff = Math.abs(x);
        for (int p = row.start() + 1; p <= end; p++) {
            int w = width.applyAsInt(text.substring(row.start(), p));
            int diff = Math.abs(w - x);
            if (diff < bestDiff) {
                best = p;
                bestDiff = diff;
            }
            if (w > x) break; // 已越过点击位置，后面的只会更远
        }
        return best;
    }
}
