/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.pm.client;

/**
 * 客户端的 PM 管理权限镜像（纯类，无 MC import，可脱机单测）。
 *
 * <p><b>存在意义</b>：在此之前客户端手里没有任何权限信息，按 P 会**无条件**打开管理看板 / 管理面板
 * ——普通玩家也能把管理界面打开（里面是空的，因为服务端不给数据，但门是开着的）。
 *
 * <h2>为什么区分"未知"与"已知不是管理员"</h2>
 * 只用一个 boolean 会出现两种误判：
 * <ul>
 *   <li>{@code false} 同时表示"还没收到服务端答案"和"确认不是管理员"。服务端主动开面板
 *       （{@code /pm panel → OpenAdminPanelS2C}）会在标志到达之前发生，此时若把它当"确认非管理员"
 *       处理，就会把刚被服务端打开的面板立刻关掉——管理员执行 {@code /pm panel} 反而看不到面板。</li>
 *   <li>因此这里把"是否已知"单独记一位：<b>入口检查要求 {@code isAdmin()}（未知=false，fail-closed）</b>，
 *       而<b>打开后的自动关闭只在 {@code known && !admin} 时触发</b>，不会与服务端抢跑。</li>
 * </ul>
 *
 * <p><b>这不是安全边界</b>：它只决定客户端要不要把界面画出来。真正的边界在服务端
 * （{@code PmServerHandlers} 每个动作都重新判定 {@code canAdminTicket}）。
 * 默认值必须是拒绝：构造后、以及任何一次 {@link #reset()} 之后都不得为 {@code true}。
 */
public final class PmClientState {

    /** 服务端是否已经明确回答过权限状态。 */
    private static volatile boolean known = false;

    /** 当前是否管理员（仅在 {@link #known()} 为真时有意义）。 */
    private static volatile boolean admin = false;

    private PmClientState() {}

    /** 服务端下发的权威答案（{@code AdminStateS2C}）。 */
    public static void setAdmin(boolean value) {
        admin = value;
        known = true;
    }

    /** 是否管理员。**未收到服务端答案时为 false**（fail-closed：宁可少给一次界面）。 */
    public static boolean isAdmin() {
        return admin;
    }

    /** 是否已收到服务端答案。 */
    public static boolean known() {
        return known;
    }

    /** 明确"已确认不是管理员"——只有在此时才允许强制关闭已打开的管理界面。 */
    public static boolean knownNonAdmin() {
        return known && !admin;
    }

    /**
     * 离开世界时复位（对称清理：不把上一个服务器的权限带进下一个）。
     * 回到"未知"而不是"已知非管理员"：新服务器会在登录时重新下发答案。
     */
    public static void reset() {
        known = false;
        admin = false;
    }
}
