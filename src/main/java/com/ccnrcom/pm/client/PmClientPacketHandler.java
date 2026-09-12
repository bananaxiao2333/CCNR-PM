/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.pm.client;

import com.ccnrcom.pm.network.PmPackets;
import net.minecraft.client.Minecraft;

/**
 * 客户端 S2C 处理入口。
 *
 * <p>本类只在客户端被类加载（由包内的 {@code DistExecutor.unsafeRunWhenOn(CLIENT, ...)} 保证），
 * 因此可以安全地引用 {@code Minecraft} 等仅客户端存在的类。
 *
 * <p>包处理器注册为 {@code consumerMainThread}，所以这里已经在主线程上，可以直接操作界面。
 */
public final class PmClientPacketHandler {

    private PmClientPacketHandler() {}

    /** 服务端要求打开举报工单面板（{@code /a} 被普通玩家使用，或 {@code /pm report}）。 */
    public static void onOpenReportScreen(PmPackets.OpenReportScreenS2C msg) {
        Minecraft.getInstance()
                .setScreen(new TicketScreen(msg.prefill, msg.roster, msg.categories, msg.maxContent, msg.maxDetail));
    }

    /**
     * 左上角常驻看板：整份替换本地数据。
     *
     * <p>不做「面板开着就不更新」这类抑制：看板在面板之下自然被遮挡，
     * 加抑制规则只会让行为难以预测。
     */
    public static void onTicketBoard(String json) {
        com.ccnrcom.pm.client.hud.TicketNoticeOverlay.accept(json);
    }

    /** 服务端下发的一页工单数据：存进本地缓存并刷新已打开的面板。 */
    public static void onTicketList(String json) {
        ClientTicketBoard.accept(json);
    }

    /** 管理动作结果：成功即清空错误并等服务的权威快照；失败把错误键交给面板显示。 */
    public static void onAdminResult(boolean ok, String messageKey) {
        if (ok) {
            ClientTicketBoard.clearError();
        } else if (messageKey != null && !messageKey.isBlank()) {
            ClientTicketBoard.setError(messageKey);
        }
    }

    /** 提交结果：交回当前打开的面板显示。面板已关闭时忽略（例如玩家抢先按了 Esc）。 */
    public static void onSubmitResult(PmPackets.SubmitResultS2C msg) {
        TicketScreen screen = TicketScreen.open();
        if (screen != null) {
            screen.onResult(msg.ok, msg.label, msg.errorsJson);
        }
    }

    /** 服务端下发的管理权限状态：存进镜像，并在权限被收回时把已打开的管理界面立刻关掉。 */
    public static void onAdminState(boolean admin) {
        PmClientState.setAdmin(admin);
        if (!PmClientState.knownNonAdmin()) {
            return; // 仍是管理员：什么都不用做
        }
        // 只看当前 screen、且走各屏自己的 onClose（它们各自负责 parent 与静态句柄的清理）。
        var screen = Minecraft.getInstance().screen;
        if (screen instanceof PmAdminScreen s) {
            s.onClose();
        } else if (screen instanceof PmBoardScreen b) {
            b.onClose();
        }
    }

    /**
     * 服务端回答「有没有 CCNR-RP、有哪些角色」。
     *
     * <p>角色选择弹窗只对**当时正开着**的交互看板下发（看板已关闭就丢掉）：
     * 一个过期的角色列表弹在别的界面上，只会在管理员点下去时才发现对象已经没了。
     */
    public static void onProfessionList(boolean available, String json) {
        PmBoardScreen screen = PmBoardScreen.open();
        if (screen != null) {
            screen.onProfessionList(available, com.ccnrcom.pm.integration.ProfessionRef.fromJson(json));
        }
    }
}
