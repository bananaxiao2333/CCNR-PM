/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.pm.permission;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 「这个玩家的管理权限上次下发的是什么值」的记账本（纯类，无 MC import，可直接单测）。
 *
 * <h2>它存在的原因</h2>
 * 客户端的权限镜像（{@code client.PmClientState}）是一道**界面门**：它不是安全边界
 * （服务端每个动作都会重新判定），但它决定「按 P 能不能打开管理界面」。
 * 而权限在**会话中途**是会变的：管理员当场 {@code /op} 一个人、权限插件热改节点、
 * 或者反过来被降权。只在登录时下发一次 boolean 的后果是：
 * 玩家上线后才拿到权限 → 客户端手里的标志永远是 {@code false} →
 * 按 P 被本地回绝、{@code /pm panel} 打开的面板还会被 {@code tick()} 立刻关掉
 * （因为客户端认为「已确认不是管理员」）。
 *
 * <p>解法是由服务端**周期复查并只在值变化时补发**。本类就是那个「值变了吗」的判断，
 * 单独抽出来是因为它是这套机制里唯一会出错的两种方向：
 * <ul>
 *   <li>判得太松（每次都发）→ 每个玩家每秒一个包，白费流量；</li>
 *   <li>判得太紧（漏发）→ 又回到「拿到权限也打不开」。</li>
 * </ul>
 * 两条都有测试钉住（{@code AdminStateTrackerTest}）。
 *
 * <p><b>不持有玩家对象、只按 UUID 记账</b>：这样它天然可以脱离服务端单测，
 * 也顺便保证了缓存规模由 {@link #forget} 控制（玩家退出即删，缓存永远不会超过在线人数）。
 */
public final class AdminStateTracker {

    private final Map<UUID, Boolean> lastSent = new HashMap<>();

    /**
     * 只查看：当前这个值**是否需要**下发（**不改变记账**）。
     *
     * <p>刻意拆成「查 / 记」两步（而不是一个会顺手改状态的 {@code shouldSend}）：
     * 下发有可能**发不出去**（客户端没装本模组、通道尚未协商完成时
     * {@code PmChannel.sendTo} 会静默跳过）。一个先改状态再发包的实现会把
     * 「没发出去」记成「已下发」，那条记录就永久堵死了重试——
     * 现象恰好又是「拿到权限后客户端依旧打不开界面」。
     * 因此调用方必须**先问、再发、发成功了才记**（{@link #markSent}）。
     */
    public boolean needsSend(UUID uuid, boolean admin) {
        Boolean previous = lastSent.get(uuid);
        return previous == null || previous != admin;
    }

    /** 记下「这个值**已经成功下发**」。只有真的发出去了才能调。 */
    public void markSent(UUID uuid, boolean admin) {
        if (uuid != null) lastSent.put(uuid, admin);
    }

    /**
     * 忘掉某个玩家。
     *
     * <p>退出时必须调用：一是避免缓存随「历史上线过的玩家」无界增长，
     * 二是保证重新登录时会**重新下发一次**（登录路径与轮询路径共用这份记账，
     * 不删的话重新登录的首包与被降权的变化都可能被这条陈旧记录吃掉）。
     */
    public void forget(UUID uuid) {
        if (uuid != null) lastSent.remove(uuid);
    }

    /** 服务端停止时清空（对称清理：进程内的下一次服务端启动不该继承上一次的记账）。 */
    public void clear() {
        lastSent.clear();
    }

    /** 记账条数（诊断与测试用：它必须始终有界于在线人数）。 */
    public int size() {
        return lastSent.size();
    }
}
