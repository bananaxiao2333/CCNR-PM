/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.pm.ticket;

import java.util.Locale;
import java.util.UUID;

/**
 * 工单标识的显示与匹配规则（纯逻辑，无 MC 依赖，可直接单测）。
 *
 * <h2>为什么用 UUID 而不是流水号</h2>
 * 早期版本为工单分配了「#0001、#0002…」这样的流水号，理由是管理员在聊天里方便引用。
 * 但流水号有两个真实缺陷：
 * <ol>
 *   <li><b>会重复</b>：序号来自 {@code max(seq)+1}，一旦存储被清空/重装（删掉 {@code tickets.json}、
 *       换库），编号就从 #0001 重新开始。两份数据合并时更是必然撞号。
 *       曾经在文档里写「序号不可回退、不可复用」——那只在**同一个存储实例内**成立，是个假保证。</li>
 *   <li><b>可枚举</b>：看到 #0007 就能推断服务器至今只开过 7 张工单，也能顺着编号去猜别的工单。</li>
 * </ol>
 * UUID 两者都没有。工单的身份**就是** UUID，全仓库唯一，与存储实例无关。
 *
 * <h2>那为什么还显示「短号」</h2>
 * 完整 UUID 有 36 个字符，没法在聊天里口述、也塞不进卡片页眉。因此
 * {@link #shortId(String)} 取规范化后的前 {@value #SHORT_LEN} 位十六进制作为**显示与输入的简写**。
 *
 * <p>**关键区别**：短号不是身份，只是前缀。真正的判定永远落在完整 UUID 上
 * （{@link #matches(String, String)}），而查询时若前缀命中多张工单，
 * 上层必须报「请提供更多字符」而不是自己挑一个——猜一个等于随机操作一张工单。
 */
public final class TicketId {

    /** 短号长度（十六进制字符数）。8 位 = 32 bit，在千级工单量下撞前缀的概率可忽略。 */
    public static final int SHORT_LEN = 8;

    /** 允许作为前缀查询的最短长度：太短会命中大量工单，查询本身没有意义。 */
    public static final int MIN_QUERY_LEN = 4;

    private TicketId() {}

    /** 新工单的标识。 */
    public static String newId() {
        return UUID.randomUUID().toString();
    }

    /** 去掉连字符并转小写，用于比较（玩家可能带连字符、也可能大写）。 */
    public static String normalize(String id) {
        if (id == null) return "";
        return id.trim().replace("-", "").toLowerCase(Locale.ROOT);
    }

    /**
     * 显示用短号（规范化后的前 {@value #SHORT_LEN} 位）；不足则原样返回。
     *
     * <p>返回空串而不是抛异常：标识来自存储或网络包，坏数据不该让界面崩掉。
     */
    public static String shortId(String id) {
        String n = normalize(id);
        return n.length() <= SHORT_LEN ? n : n.substring(0, SHORT_LEN);
    }

    /** 面向人的标签，如 {@code #a3f2c1d8}。 */
    public static String label(String id) {
        String s = shortId(id);
        return s.isEmpty() ? "#?" : "#" + s;
    }

    /** 是否是合法的完整 UUID。 */
    public static boolean isFullId(String id) {
        try {
            UUID.fromString(id == null ? "" : id.trim());
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** 是否可以作为前缀查询（长度达标且只含十六进制字符）。 */
    public static boolean isValidQuery(String query) {
        String n = normalize(query);
        if (n.length() < MIN_QUERY_LEN) return false;
        for (int i = 0; i < n.length(); i++) {
            char c = n.charAt(i);
            boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f');
            if (!hex) return false;
        }
        return true;
    }

    /** 给定工单标识是否匹配查询串（完整 UUID 或前缀，忽略大小写与连字符）。 */
    public static boolean matches(String id, String query) {
        String q = normalize(query);
        if (q.isEmpty()) return false;
        return normalize(id).startsWith(q);
    }
}
