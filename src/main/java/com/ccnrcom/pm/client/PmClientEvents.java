/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.pm.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 客户端 tick：处理按键触发与断线清理。
 *
 * <p>按键在 {@code ClientTickEvent.End} 里轮询（{@code consumeClick()}），
 * 而不是挂在 GLFW 回调上：轮询天然只在游戏逻辑线程执行，不需要额外的线程切换，
 * 这也是 CCNR-RP 的 K 面板做法。
 */
@Mod.EventBusSubscriber(modid = com.ccnrcom.pm.CCNRPMMod.MODID, value = Dist.CLIENT)
public final class PmClientEvents {

    private PmClientEvents() {}

    /** 长按 P 判定阈值（毫秒）。超过它就直接打开 PM 管理面板，而不是进入卡片交互模式。 */
    private static final long LONG_PRESS_MS = 600L;

    /** 本次按下 P 的起始时刻；0 表示当前没按住。 */
    private static long pressStartedAt;

    /** 本次按住是否已经触发过长按（避免一直按着反复开面板）。 */
    private static boolean longPressFired;

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();

        // 界面打开时按键由 Screen 自己处理（这里只管「游戏画面中」的 P）
        if (mc.screen != null) {
            pressStartedAt = 0L;
            longPressFired = false;
            return;
        }

        // consumeClick 只在按下瞬间为真一次 → 用它在按下的那一刻进入交互模式
        while (PmClientSetup.OPEN_PANEL.consumeClick()) {
            pressStartedAt = Util.getMillis();
            longPressFired = false;
            // P 的两条路径（交互看板 / 管理面板）都是**管理界面**：看板卡片上的「认领 / 忽略 / 关闭 /
            // 释放」与头像上的玩家动作都是管理动作，服务端也只把看板数据发给管理员。所以没有权限时
            // 一律不开，只给一条提示——否则普通玩家能把管理界面打开（里面是空的，但门开着）。
            if (!PmClientState.isAdmin()) {
                denyNoPermission(mc);
                continue; // 不武装长按判定：这次按下整段都不该有任何结果
            }
            // 短按的即时效果：鼠标出来 + 左上角卡片可点。
            // 若玩家继续按住超过阈值，下面的长按逻辑会改为直接打开管理面板。
            mc.setScreen(new PmBoardScreen());
        }

        // 长按：按住不放超过阈值 → 直接打开 PM 管理面板（并替掉刚打开的交互看板）
        if (pressStartedAt != 0L && !longPressFired) {
            long held = Util.getMillis() - pressStartedAt;
            if (held >= LONG_PRESS_MS) {
                longPressFired = true;
                if (PmClientState.isAdmin()) {
                    mc.setScreen(new PmAdminScreen());
                }
            }
        }
        if (!isKeyDown(mc)) {
            pressStartedAt = 0L;
            longPressFired = false;
        }
    }

    /** 无管理权限时的统一回绝：只提示，不开任何界面（判据单一入口，避免各处各写一份）。 */
    private static void denyNoPermission(Minecraft mc) {
        if (mc.player != null) {
            mc.player.displayClientMessage(Component.translatable("ccnr_pm.command.no_permission"), true);
        }
    }

    /** P 键当前是否处于按下状态。 */
    private static boolean isKeyDown(Minecraft mc) {
        // 在交互看板打开期间 P 已被 Screen 消费，这里用原始输入状态判断长按
        return InputConstants.isKeyDown(
                mc.getWindow().getWindow(), PmClientSetup.OPEN_PANEL.getKey().getValue());
    }

    /**
     * 玩家退出世界时清空客户端的工单缓存。
     *
     * <p>对称清理：不把上一个服务器的工单列表带进下一个，
     * 否则切服后打开面板会先闪一下旧数据。
     */
    @SubscribeEvent
    public static void onClientDisconnect(net.minecraftforge.client.event.ClientPlayerNetworkEvent.LoggingOut event) {
        ClientTicketBoard.reset();
        // 权限镜像一并复位：不把上一个服务器的管理权限带进下一个（回"未知"，等服务端重新下发）
        PmClientState.reset();
        com.ccnrcom.pm.client.hud.TicketNoticeOverlay.clear();
    }
}
