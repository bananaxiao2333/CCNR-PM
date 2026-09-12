/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.pm.client.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ccnrcom.pm.client.ui.PmChipModel.Candidate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 胶囊输入模型的边界门禁。
 *
 * <p>用户要求「关联玩家像 QQ 一样变成一个整体」，并要求**只能从在线名单里选**。
 * 于是这里有三类极易出错、又必须离线测死的东西：
 * ①去重与上限（重复胶囊＝错误的操作反馈）；②退格手感（文字空时删胶囊，多删一个就误删了别人的名字）；
 * ③**提交值只含胶囊**（把过滤用的半截文字提交上去，等于让服务端校验去挡玩家的手滑）。
 */
class PmChipModelTest {

    private static List<Candidate> roster(String... names) {
        List<Candidate> out = new java.util.ArrayList<>();
        for (int i = 0; i < names.length; i++) {
            out.add(new Candidate("uuid-" + i, names[i]));
        }
        return out;
    }

    @Test
    @DisplayName("加胶囊：去重（忽略大小写）、超上限拒绝")
    void addChipDedupesAndCaps() {
        PmChipModel m = new PmChipModel(2);
        assertTrue(m.addChip(new Candidate("u1", "Alice")));
        assertFalse(m.addChip(new Candidate("u2", "alice")), "同名（大小写不同）不得重复添加");
        assertTrue(m.addChip(new Candidate("u3", "Bob")));
        assertTrue(m.isFull());
        assertFalse(m.addChip(new Candidate("u4", "Carol")), "已达上限必须拒绝");
        assertFalse(m.addChip(null));
        assertFalse(m.addChip(new Candidate("u5", "  ")), "空名字不得成为胶囊");
    }

    @Test
    @DisplayName("提交值只含胶囊，逗号分隔（未成胶囊的输入文字绝不提交）")
    void valueContainsChipsOnly() {
        PmChipModel m = new PmChipModel(8);
        m.setText("Bob"); // 只是过滤条件
        assertEquals("", m.value(), "没确认的文字不该被提交");
        m.addChip(new Candidate("u1", "Alice"));
        m.setText("Bo");
        m.addChip(new Candidate("u2", "Bob"));
        assertEquals("Alice, Bob", m.value());
        m.removeChip(0);
        assertEquals("Bob", m.value());
    }

    @Test
    @DisplayName("退格：先删文字，文字空了才删最后一个胶囊，再空则什么都不做")
    void backspaceOrder() {
        PmChipModel m = new PmChipModel(8);
        m.addChip(new Candidate("u1", "Alice"));
        m.addChip(new Candidate("u2", "Bob"));
        m.setText("xy");
        assertTrue(m.backspace());
        assertEquals("x", m.text());
        assertTrue(m.backspace());
        assertEquals("", m.text());
        assertEquals(2, m.size(), "删文字时不得动胶囊");
        assertTrue(m.backspace());
        assertEquals(1, m.size(), "文字空了才删最后一个胶囊");
        assertTrue(m.backspace());
        assertEquals(0, m.size());
        assertFalse(m.backspace(), "全空时退格无事可做");
    }

    @Test
    @DisplayName("过滤文字收任何可见字符（中文、空格、符号都收），只挡控制字符")
    void filterAcceptsEveryVisibleCharacter() {
        PmChipModel m = new PmChipModel(8);
        assertTrue(m.insert('中'), "中文输入法打进来的字必须显示出来，否则玩家看到的就是「打不进字」");
        assertTrue(m.insert(' '));
        assertTrue(m.insert('-'));
        assertTrue(m.insert('A'));
        assertEquals("中 -A", m.text());
        assertFalse(m.insert('\n'), "控制字符没有可输入的意义");
        assertFalse(m.insert((char) 127));
        assertFalse(m.insert('§'), "格式符不得混进过滤文字（会污染界面文本）");

        m.setText("A l i c e-中");
        assertEquals("A l i c e-中", m.text(), "粘贴同样只过滤控制字符");
        assertEquals(11, m.caret(), "过滤后光标应落在末尾");
    }

    @Test
    @DisplayName("过滤文字有长度上限：打字与粘贴都不会无限长")
    void filterTextIsCapped() {
        PmChipModel m = new PmChipModel(8);
        for (int i = 0; i < PmChipModel.MAX_TEXT + 10; i++) {
            m.insert('a');
        }
        assertEquals(PmChipModel.MAX_TEXT, m.text().length());
        assertFalse(m.insert('a'), "已达长度上限不再收");

        m.setText("x".repeat(PmChipModel.MAX_TEXT + 5));
        assertEquals(PmChipModel.MAX_TEXT, m.text().length(), "粘贴也要截断");
    }

    @Test
    @DisplayName("中文过滤词匹配不到人，也绝不进入提交值")
    void chineseFilterNeverBecomesValue() {
        PmChipModel m = new PmChipModel(8);
        m.setText("阿尔");
        assertTrue(m.candidates(roster("Alice", "Bob"), "self").isEmpty(), "中文过滤词不该匹配到人");
        assertEquals("", m.value(), "过滤文字永远不是提交值，只有胶囊才是");
    }

    @Test
    @DisplayName("候选：前缀匹配、排除自己与已选、输入为空不给候选、满员不给候选")
    void candidatesFiltering() {
        PmChipModel m = new PmChipModel(2);
        List<Candidate> all = roster("Alice", "Alfred", "Bob");

        assertEquals(List.of(), m.candidates(all, "Zed"), "没有输入时不弹候选（免得一进面板就挡住其它字段）");

        m.setText("Al");
        List<Candidate> hit = m.candidates(all, "Zed");
        assertEquals(2, hit.size());
        assertEquals("Alice", hit.get(0).name());
        assertEquals("Alfred", hit.get(1).name());

        assertTrue(m.candidates(roster("Alice"), "Alice").isEmpty(), "不该把自己列为可举报对象");

        m.addChip(new Candidate("u", "Alice"));
        m.setText("Al");
        assertEquals(
                List.of("Alfred"),
                m.candidates(all, "Zed").stream().map(Candidate::name).toList(),
                "已选的要从候选中去掉");

        m.addChip(new Candidate("u2", "Bob"));
        assertTrue(m.isFull());
        assertTrue(m.candidates(roster("Bobby"), "Zed").isEmpty(), "满员后不再给候选");
    }

    @Test
    @DisplayName("候选前缀匹配忽略大小写")
    void candidatesAreCaseInsensitive() {
        PmChipModel m = new PmChipModel(8);
        m.setText("aLi");
        assertEquals(1, m.candidates(roster("Alice"), "Zed").size());
    }

    @Test
    @DisplayName("高亮与滚动永远落在合法范围内（越界会导致回车选错人）")
    void highlightStaysInRange() {
        PmChipModel m = new PmChipModel(8);
        m.moveHighlight(-1, 3);
        assertEquals(0, m.highlight(), "上越界应停在第一项");
        m.moveHighlight(1, 3);
        assertEquals(1, m.highlight());
        m.moveHighlight(5, 3);
        assertEquals(2, m.highlight(), "下越界应停在最后一项");
        m.moveHighlight(1, 0);
        assertEquals(0, m.highlight(), "没有候选时归零");

        m.setScroll(-5);
        assertEquals(0, m.scroll(), "滚动偏移不能为负");
    }

    @Test
    @DisplayName("加胶囊会清掉当前输入文字（避免同一个名字又留在过滤框里）")
    void addingChipClearsText() {
        PmChipModel m = new PmChipModel(8);
        m.setText("Ali");
        m.moveCaret(-1);
        m.addChip(new Candidate("u", "Alice"));
        assertEquals("", m.text());
        assertEquals(0, m.caret());
    }

    @Test
    @DisplayName("clear 清空全部（重新打开面板/提交成功后复用）")
    void clearResetsEverything() {
        PmChipModel m = new PmChipModel(8);
        m.addChip(new Candidate("u", "Alice"));
        m.setText("Bo");
        m.setScroll(3);
        m.clear();
        assertTrue(m.chips().isEmpty());
        assertEquals("", m.text());
        assertEquals("", m.value());
        assertEquals(0, m.scroll());
    }
}
