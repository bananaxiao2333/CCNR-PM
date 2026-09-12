/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.pm.client;

import com.ccnrcom.pm.ticket.TicketPage;
import java.util.Optional;

/**
 * 客户端持有的「工单面板最新一页数据」。
 *
 * <p>为什么只是一层极薄的缓存：面板的数据**只有服务端一个权威源**。
 * 客户端从不自己改本地列表（认领成功后不手动把状态改成 claimed），
 * 而是等服务端回推一份新快照——否则「本地以为办结了、服务端其实没办成」这类不一致
 * 就永远无从察觉。这里的缓存只负责「把最近一次快照给当前打开的界面用」。
 *
 * <p>线程：只在客户端主线程读写（包处理器注册为 {@code consumerMainThread}）。
 */
public final class ClientTicketBoard {

    private static volatile TicketPage page;
    private static volatile String lastErrorKey = "";

    private ClientTicketBoard() {}

    /** 用服务端下发的一页数据替换本地缓存，并让已打开的面板刷新。 */
    public static void accept(String json) {
        Optional<TicketPage> parsed = TicketPage.fromJson(json);
        if (parsed.isEmpty()) {
            // 解析失败不清空旧数据：保留上一次可用快照比显示一个空面板更有用
            return;
        }
        page = parsed.get();
        PmAdminScreen.refreshIfOpen();
    }

    public static Optional<TicketPage> page() {
        return Optional.ofNullable(page);
    }

    /** 是否有数据可显示（决定面板显示「加载中」还是「没有工单」）。 */
    public static boolean hasData() {
        return page != null;
    }

    public static void setError(String key) {
        lastErrorKey = key == null ? "" : key;
        PmAdminScreen.refreshIfOpen();
    }

    public static void clearError() {
        lastErrorKey = "";
    }

    public static String errorKey() {
        return lastErrorKey;
    }

    /** 断开连接时清空（对称清理：不该把上一个服务器的工单带进下一个）。 */
    public static void reset() {
        page = null;
        lastErrorKey = "";
    }

    /** 主动请求刷新（打开面板时、动作后由服务端推送；切换筛选时由界面调用）。 */
    public static void request(String filter, int page) {
        com.ccnrcom.pm.network.PmChannel.sendToServer(
                new com.ccnrcom.pm.network.PmPackets.RequestTicketsC2S(filter, page));
    }
}
