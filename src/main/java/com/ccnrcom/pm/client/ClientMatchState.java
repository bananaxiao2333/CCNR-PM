/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.pm.client;

import com.ccnrcom.pm.integration.MatchSnapshot;
import java.util.Optional;

/**
 * 客户端持有的「最近一份对局快照」。
 *
 * <p>与 {@link ClientTicketBoard} 同一思路：面板的数据**只有服务端一个权威源**，
 * 这里只是把最近一次快照交给当前打开的界面用，客户端从不自己改本地对局状态
 * （切幕成功后不手动把「当前幕」改成目标幕，而是等服务端回推一份新快照——
 * 否则「本地以为切幕了、服务端其实拒绝了」这类不一致就永远无从察觉）。
 *
 * <h2>为什么 {@link #accept} **不**调用 {@code PmAdminScreen.refreshIfOpen()}</h2>
 * 对局/事件页签**不持有任何 widget**，每帧直接从本缓存取值渲染，因此新数据本身不需要重建界面。
 * 而 {@code refreshIfOpen()} 会 `clearWidgets()` 后重建**所有**页签——
 * 对局快照是**每秒刷新**的活数据，那样做等于每秒把工单页签的备注输入框拆一次重建，
 * 症状是「备注打字打到一半光标就没了」（0.9 时代的焦点问题换了个触发源）。
 * 需要重建的情形（工单动作后）仍由 {@link ClientTicketBoard#accept} 负责。
 *
 * <p>线程：只在客户端主线程读写（包处理器注册为 {@code consumerMainThread}）。
 */
public final class ClientMatchState {

    private static volatile MatchSnapshot snapshot;
    private static volatile String lastErrorKey = "";

    private ClientMatchState() {}

    /**
     * 用服务端下发的快照替换本地缓存。
     *
     * <p>解析失败**不清空旧数据**：保留上一次可用快照比把一个已经显示着倒计时的面板变空更有用。
     */
    public static void accept(String json) {
        MatchSnapshot.fromJson(json).ifPresent(s -> snapshot = s);
    }

    public static Optional<MatchSnapshot> snapshot() {
        return Optional.ofNullable(snapshot);
    }

    /** 服务端是否装了 CCNR-RP（**必须先有快照**，未知时不得谎报「有」——空快照返回 false）。 */
    public static boolean rpAvailable() {
        MatchSnapshot s = snapshot;
        return s != null && s.available();
    }

    public static void setError(String key) {
        lastErrorKey = key == null ? "" : key;
    }

    public static void clearError() {
        lastErrorKey = "";
    }

    public static String errorKey() {
        return lastErrorKey;
    }

    /** 断开连接时清空（对称清理：不把上一个服务器的对局带进下一个）。 */
    public static void reset() {
        snapshot = null;
        lastErrorKey = "";
    }

    /** 主动请求一份新快照（面板打开时、以及打开期间每秒一次）。 */
    public static void request() {
        com.ccnrcom.pm.network.PmChannel.sendToServer(new com.ccnrcom.pm.network.PmPackets.RequestMatchStateC2S());
    }
}
