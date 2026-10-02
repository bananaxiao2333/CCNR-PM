/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.pm.permission;

import com.ccnrcom.pm.network.PmChannel;
import com.ccnrcom.pm.network.PmPackets;
import java.util.List;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * 管理权限状态的**服务端下发器**：让客户端的权限镜像真正跟上服务端的真值。
 *
 * <h2>修的是什么（用户报的现象：拿到权限后客户端依旧打不开东西）</h2>
 * {@code AdminStateS2C} 原先只在两个时刻下发：玩家登录、以及某次管理请求被拒
 * （被拒时补发 {@code false}，用来纠正「权限被收回后标志仍是 true」）。
 * 两条路径都只能把标志往 <b>false</b> 纠正，因此**「上线后才拿到权限」根本无法收敛**：
 *
 * <ol>
 *   <li>上线时不是管理员 → 客户端 {@code known=true, admin=false}；</li>
 *   <li>管理员执行 {@code /op 他}、或用权限插件授了 {@code ccnrpm.admin.ticket}；</li>
 *   <li>服务端此刻认为他是管理员了，但客户端还停在 {@code false}——而且它是**「已确认」的 false**：
 *       <ul>
 *         <li>按 P → 本地直接回绝（那是一次「界面门」判定，不会向服务端求证）；</li>
 *         <li>{@code /pm panel} → 服务端校验通过并下发打开面板，客户端打开后
 *             {@code PmAdminScreen.tick()} 每帧复核，看到「已确认不是管理员」→ <b>立刻把面板关掉</b>。</li>
 *       </ul>
 *   </li>
 * </ol>
 *
 * <p>也就是说：**不是界面画不出来，是客户端手里的权限是过期且自认为权威的**。
 *
 * <h2>解法：服务端周期复查 + 只在值变化时补发</h2>
 * 每 {@link #INTERVAL_TICKS} tick 复查一次在线玩家的 {@code canAdminTicket}，
 * 与「上次下发的值」不同才发包。于是：
 * <ul>
 *   <li>稳态**零流量**（值不变就不发），不是「每人每秒一个包」；</li>
 *   <li>授权/降权在 1 秒内收敛（降权方向也收敛，且 {@code PmAdminScreen} 的自动关闭因此才真的有效）；</li>
 *   <li>不依赖任何权限插件的事件——{@code ops.json} 热改、LuckPerms 改节点这类
 *       「服务端之外发生的事」没有 Forge 事件可听，轮询是唯一可靠的做法。</li>
 * </ul>
 *
 * <p>另一条独立通路是 {@code /pm panel}：那条命令**已经**在服务端用 {@code canAdminTicket} 判过权限，
 * 因此客户端收到 {@code OpenAdminPanelS2C} 时可以直接把标志置为 true（见
 * {@code client.PmClientPacketHandler.onOpenAdminPanel}）——不必等这一秒的轮询。
 *
 * <p>本类**不是安全边界**：它下发的只是一个界面门。真正的判定仍在服务端每个动作里
 * （{@code PmServerHandlers} 重新调 {@code canAdminTicket}），改造过的客户端把标志改成 true
 * 也读不到任何数据、做不成任何管理动作。
 */
public final class AdminStateSync {

    /**
     * 复查周期（服务端 tick）。1 秒。
     *
     * <p>取 1 秒是因为「拿到权限之后立刻按 P」是最自然的动作顺序，
     * 更长的间隔会让管理员先被回绝一次再自己反应过来；而复查本身只是一次布尔判定，
     * 发包又只在值变化时发生，因此这个频率的代价可以忽略。
     */
    private static final int INTERVAL_TICKS = 20;

    /** 「上次下发了什么」的记账（纯类，可单测）。 */
    private static final AdminStateTracker TRACKER = new AdminStateTracker();

    private static int counter;

    private AdminStateSync() {}

    /**
     * 立即下发一次（登录时调用）。
     *
     * <p>**无视记账、无条件下发**：登录路径必须给出权威答案，不能因为
     * 「这条 UUID 的记录还在」而省掉这一包。但**记账只在真的发出去之后才写**——
     * 客户端没装本模组、或通道还没协商好时 {@code sendTo} 会静默跳过，
     * 那时必须留给轮询重试，否则又是「拿到权限也打不开」。
     *
     * @return 是否真的发出去了
     */
    public static boolean push(ServerPlayer player) {
        if (player == null) return false;
        boolean admin = PmPermissions.canAdminTicket(player);
        if (!deliver(player, admin)) return false;
        TRACKER.markSent(player.getUUID(), admin);
        return true;
    }

    /**
     * 服务端每 tick 调用（内部节流到 {@link #INTERVAL_TICKS}）。
     *
     * <p>只复查**在线玩家**：离线玩家的镜像会在下次登录时由 {@link #push} 重新下发，
     * 为他们保留记账只会让缓存无界增长。
     *
     * <p>这条路径同时是「通道晚一点才可用」与「登录首包没送出去」的**兜底**：
     * 只要记账里没有对应的成功记录，它就会一直重试到发出去为止。
     */
    public static void onServerTick(MinecraftServer server) {
        if (server == null) return;
        if (++counter < INTERVAL_TICKS) return;
        counter = 0;

        List<ServerPlayer> players = server.getPlayerList().getPlayers();
        for (ServerPlayer player : players) {
            boolean admin = PmPermissions.canAdminTicket(player);
            if (!TRACKER.needsSend(player.getUUID(), admin)) continue;
            if (deliver(player, admin)) {
                TRACKER.markSent(player.getUUID(), admin);
            }
            // 发不出去（通道不在）就不记账：下一轮继续试。空转的代价只是一次布尔判定
        }
    }

    /** 单点发包（`sendTo` 的返回值就是「真的发出去了吗」）。 */
    private static boolean deliver(ServerPlayer player, boolean admin) {
        return PmChannel.sendTo(player, new PmPackets.AdminStateS2C(admin));
    }

    /** 玩家退出：丢掉记账（缓存因此有界于在线人数）。 */
    public static void forget(UUID uuid) {
        TRACKER.forget(uuid);
    }

    /** 服务端停止：清空记账并复位节流计数（对称清理）。 */
    public static void shutdown() {
        TRACKER.clear();
        counter = 0;
    }

    /** 当前的复查周期（诊断/测试用）。 */
    public static int intervalTicks() {
        return INTERVAL_TICKS;
    }

    /** 记账条数（诊断用；正常应等于在线人数）。 */
    public static int trackedPlayers() {
        return TRACKER.size();
    }
}
