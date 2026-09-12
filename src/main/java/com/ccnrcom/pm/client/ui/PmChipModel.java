/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.pm.client.ui;

import com.ccnrcom.pm.ticket.ReportValidator;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 「关联玩家」胶囊输入框的**模型**（纯逻辑，无 MC 依赖，可直接单测）。
 *
 * <h2>为什么要有它</h2>
 * 用户要求这块像 QQ 一样「选中的玩家变成一个整体（胶囊）」。胶囊涉及一堆边界：
 * 去重、上限、退格删胶囊、候选过滤与高亮、以及**提交时到底送什么**。
 * 这些全是纯数据规则，抽出来才能离线测；渲染与鼠标命中留给 {@link PmChipInput}。
 *
 * <h2>只收在线名单里的玩家（用户明确要求）</h2>
 * 输入框里的文字**只是过滤条件**，不是要提交的内容：只有成为胶囊的名字才会进入
 * {@link #value()}。因此玩家不可能靠手打一串不存在的名字建单——
 * 服务端另有一道「在线 或 本服见过面」的校验（见 {@code KnownPlayers}），两道合起来才闭环。
 *
 * <p>反过来，「只收名单里的」**不等于**「过滤文字也必须是玩家名」：过滤文字任何可见字符都收
 * （中文输入法打进来的字当然也算），没匹配到人就由界面说清楚「没有匹配的在线玩家」。
 * 把过滤文字也限成玩家名字符曾经造成「框能点亮但打不进字」，见 {@link #allowed(char)}。
 */
public final class PmChipModel {

    /** 一个候选/已选玩家。 */
    public record Candidate(String uuid, String name) {
        public Candidate {
            uuid = uuid == null ? "" : uuid;
            name = name == null ? "" : name;
        }
    }

    /** 候选列表最多显示多少个（面板里的弹层看不了更多）。 */
    public static final int MAX_CANDIDATES = 32;

    /** 过滤文字最多这么长：输入框就那么大，再长也只能被裁掉。 */
    public static final int MAX_TEXT = 32;

    /**
     * 过滤文字的字符规则：**只挡控制字符**（以及 {@code §} 这类格式符），其余一律放行。
     *
     * <p>这里曾经只允许 {@code [A-Za-z0-9_]}（就是原版玩家名字符集），理由是「反正只能从在线名单里选，
     * 别的字符没有意义」。代价是中文输入法打进来的字**一个都不显示**：玩家看到的是
     * 「框点得亮、光标也在闪，却打不进任何字」，只能得出「这个输入框坏了」的结论（0.12.0 的实机反馈）。
     *
     * <p>过滤文字**不参与提交**（提交的只有 {@link #value()} 里的胶囊），所以限制它得不到任何好处。
     */
    static boolean allowed(char c) {
        return c >= ' ' && c != 127 && c != 167;
    }

    private final int maxChips;
    private final List<Candidate> chips = new ArrayList<>();

    /** 当前正在输入的文字（过滤用，未成为胶囊）。 */
    private String text = "";

    private int caret;
    private int highlight;
    private int scroll;

    public PmChipModel(int maxChips) {
        this.maxChips = Math.max(1, maxChips);
    }

    // ------------------------------------------------------------------
    // 胶囊
    // ------------------------------------------------------------------

    public List<Candidate> chips() {
        return List.copyOf(chips);
    }

    public boolean isFull() {
        return chips.size() >= maxChips;
    }

    public int size() {
        return chips.size();
    }

    /**
     * 加一个胶囊。
     *
     * <p>去重按名字（忽略大小写）：服务端也会去重，但界面上出现两个同名胶囊等于给出错误的操作反馈。
     *
     * @return 是否真的加进去了（已满或重复时为 false）
     */
    public boolean addChip(Candidate candidate) {
        if (candidate == null || candidate.name().isBlank()) return false;
        if (isFull()) return false;
        for (Candidate c : chips) {
            if (c.name().equalsIgnoreCase(candidate.name())) return false;
        }
        chips.add(candidate);
        text = "";
        caret = 0;
        highlight = 0;
        scroll = 0;
        return true;
    }

    /** 删掉第 {@code index} 个胶囊。 */
    public boolean removeChip(int index) {
        if (index < 0 || index >= chips.size()) return false;
        chips.remove(index);
        return true;
    }

    // ------------------------------------------------------------------
    // 当前输入文字
    // ------------------------------------------------------------------

    public String text() {
        return text;
    }

    public int caret() {
        return caret;
    }

    /** 光标是否在文字最前面（退格删胶囊的条件之一）。 */
    public boolean caretAtStart() {
        return caret <= 0;
    }

    /** 直接设置文字（粘贴用；同样只过滤控制字符，并截断到 {@link #MAX_TEXT}）。 */
    public void setText(String raw) {
        StringBuilder sb = new StringBuilder();
        if (raw != null) {
            for (char c : raw.toCharArray()) {
                if (allowed(c)) sb.append(c);
                if (sb.length() >= MAX_TEXT) break;
            }
        }
        text = sb.toString();
        caret = text.length();
        highlight = 0;
        scroll = 0;
    }

    /**
     * 在光标处插入字符。
     *
     * <p>只有控制字符会被忽略——中文、空格、符号都照收：它们打不出来不代表玩家看不到任何反馈，
     * 这个区别正是「输入框坏了」和「这个名字没匹配到人」的分界线。
     */
    public boolean insert(char c) {
        if (!allowed(c)) return false;
        if (isFull()) return false;
        if (text.length() >= MAX_TEXT) return false;
        caret = Math.max(0, Math.min(caret, text.length()));
        text = text.substring(0, caret) + c + text.substring(caret);
        caret++;
        highlight = 0;
        return true;
    }

    /**
     * 退格。
     *
     * <p>文字为空且光标在开头时**删掉最后一个胶囊**——这是胶囊输入的通用手感
     * （QQ、微信都是这样），否则只能去点那个很小的 ×。
     *
     * @return 是否改变了任何东西
     */
    public boolean backspace() {
        if (caret > 0 && !text.isEmpty()) {
            int at = Math.max(0, Math.min(caret, text.length()));
            text = text.substring(0, at - 1) + text.substring(at);
            caret = at - 1;
            return true;
        }
        if (!text.isEmpty()) {
            text = "";
            caret = 0;
            return true;
        }
        if (!chips.isEmpty()) {
            chips.remove(chips.size() - 1);
            return true;
        }
        return false;
    }

    /** Delete：删光标后的字符。 */
    public boolean deleteForward() {
        int at = Math.max(0, Math.min(caret, text.length()));
        if (at >= text.length()) return false;
        text = text.substring(0, at) + text.substring(at + 1);
        return true;
    }

    public void moveCaret(int delta) {
        caret = Math.max(0, Math.min(text.length(), caret + delta));
    }

    public void home() {
        caret = 0;
    }

    public void end() {
        caret = text.length();
    }

    public void clear() {
        chips.clear();
        text = "";
        caret = 0;
        highlight = 0;
        scroll = 0;
    }

    // ------------------------------------------------------------------
    // 提交值
    // ------------------------------------------------------------------

    /**
     * 提交用的字段值：**只有胶囊**，逗号分隔（与服务端 {@code ReportValidator.parseTargets} 的输入格式一致）。
     *
     * <p>未成胶囊的输入文字**故意不提交**：它是过滤条件，不是「我要举报的人」。
     */
    public String value() {
        StringBuilder sb = new StringBuilder();
        for (Candidate c : chips) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(c.name());
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // 候选
    // ------------------------------------------------------------------

    /**
     * 按当前输入做前缀匹配。
     *
     * <p>已选中的、以及自己（举报自己没意义）会被排除；输入为空时不给候选——
     * 否则一进面板就弹一大片在线名单挡住其它字段。
     */
    public List<Candidate> candidates(List<Candidate> roster, String selfName) {
        List<Candidate> out = new ArrayList<>();
        if (roster == null || roster.isEmpty()) return out;
        if (isFull()) return out;
        String token = text.trim().toLowerCase(Locale.ROOT);
        if (token.isEmpty()) return out;
        for (Candidate c : roster) {
            if (c == null || c.name().isBlank()) continue;
            if (selfName != null && c.name().equalsIgnoreCase(selfName)) continue;
            if (containsName(chips, c.name())) continue;
            if (c.name().toLowerCase(Locale.ROOT).startsWith(token)) {
                out.add(c);
                if (out.size() >= MAX_CANDIDATES) break;
            }
        }
        return out;
    }

    private static boolean containsName(List<Candidate> list, String name) {
        for (Candidate c : list) {
            if (c.name().equalsIgnoreCase(name)) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------
    // 高亮与滚动（弹层内部状态）
    // ------------------------------------------------------------------

    public int highlight() {
        return highlight;
    }

    public void setHighlight(int index) {
        highlight = Math.max(0, index);
    }

    /** 上下移动高亮；{@code size} 为当前候选数（高亮始终落在合法范围内）。 */
    public void moveHighlight(int dir, int size) {
        if (size <= 0) {
            highlight = 0;
            return;
        }
        int next = highlight + dir;
        if (next < 0) next = 0;
        if (next >= size) next = size - 1;
        highlight = next;
    }

    public int scroll() {
        return scroll;
    }

    public void setScroll(int value) {
        scroll = Math.max(0, value);
    }

    /** 已选数量上限（与 {@link ReportValidator#MAX_TARGETS} 同一个来源）。 */
    public int maxChips() {
        return maxChips;
    }
}
