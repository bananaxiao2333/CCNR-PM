/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.pm.network;

import net.minecraft.network.Connection;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

/**
 * 网络通道 {@code ccnr_pm:main}。
 *
 * <p>注册顺序即协议编号（{@code id++}）：**新增包只能追加在末尾**，插在中间会让已发布客户端的编号整体错位，
 * 表现为随机解析失败而不是「版本不兼容」这种可读错误。
 *
 * <p>客户端未安装本模组时不构成问题：{@link #sendTo} 会静默跳过（服务端功能照常，只是玩家看不到面板）。
 * 玩家侧的核心路径（{@code /a} → 面板）本就要求客户端装了模组，没有通道时跳过是正确降级。
 */
public final class PmChannel {

    public static final String PROTOCOL_VERSION = "1";

    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation("ccnr_pm", "main"),
            () -> PROTOCOL_VERSION,
            PROTOCOL_VERSION::equals,
            PROTOCOL_VERSION::equals);

    private static int id = 0;

    private PmChannel() {}

    /** 在 mod 构造期调用（两端都要执行）。 */
    public static void register() {
        CHANNEL.messageBuilder(PmPackets.OpenReportScreenS2C.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(PmPackets.OpenReportScreenS2C::encode)
                .decoder(PmPackets.OpenReportScreenS2C::new)
                .consumerMainThread(PmPackets.OpenReportScreenS2C::handle)
                .add();

        CHANNEL.messageBuilder(PmPackets.SubmitTicketC2S.class, id++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(PmPackets.SubmitTicketC2S::encode)
                .decoder(PmPackets.SubmitTicketC2S::new)
                .consumerMainThread(PmPackets.SubmitTicketC2S::handle)
                .add();

        CHANNEL.messageBuilder(PmPackets.SubmitResultS2C.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(PmPackets.SubmitResultS2C::encode)
                .decoder(PmPackets.SubmitResultS2C::new)
                .consumerMainThread(PmPackets.SubmitResultS2C::handle)
                .add();

        // 新增包一律追加在末尾：注册顺序即协议编号，插在中间会让已发布客户端整体错位
        CHANNEL.messageBuilder(PmPackets.TicketBoardS2C.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(PmPackets.TicketBoardS2C::encode)
                .decoder(PmPackets.TicketBoardS2C::new)
                .consumerMainThread(PmPackets.TicketBoardS2C::handle)
                .add();

        // 管理面板
        CHANNEL.messageBuilder(PmPackets.RequestTicketsC2S.class, id++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(PmPackets.RequestTicketsC2S::encode)
                .decoder(PmPackets.RequestTicketsC2S::new)
                .consumerMainThread(PmPackets.RequestTicketsC2S::handle)
                .add();

        CHANNEL.messageBuilder(PmPackets.TicketListS2C.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(PmPackets.TicketListS2C::encode)
                .decoder(PmPackets.TicketListS2C::new)
                .consumerMainThread(PmPackets.TicketListS2C::handle)
                .add();

        CHANNEL.messageBuilder(PmPackets.TicketActionC2S.class, id++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(PmPackets.TicketActionC2S::encode)
                .decoder(PmPackets.TicketActionC2S::new)
                .consumerMainThread(PmPackets.TicketActionC2S::handle)
                .add();

        CHANNEL.messageBuilder(PmPackets.AdminResultS2C.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(PmPackets.AdminResultS2C::encode)
                .decoder(PmPackets.AdminResultS2C::new)
                .consumerMainThread(PmPackets.AdminResultS2C::handle)
                .add();

        CHANNEL.messageBuilder(PmPackets.OpenAdminPanelS2C.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(PmPackets.OpenAdminPanelS2C::encode)
                .decoder(PmPackets.OpenAdminPanelS2C::new)
                .consumerMainThread(PmPackets.OpenAdminPanelS2C::handle)
                .add();

        // 看板头像上的玩家动作（传送 / 刷出 / 送回阴间）与角色列表
        CHANNEL.messageBuilder(PmPackets.PlayerActionC2S.class, id++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(PmPackets.PlayerActionC2S::encode)
                .decoder(PmPackets.PlayerActionC2S::new)
                .consumerMainThread(PmPackets.PlayerActionC2S::handle)
                .add();

        CHANNEL.messageBuilder(PmPackets.RequestProfessionsC2S.class, id++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(PmPackets.RequestProfessionsC2S::encode)
                .decoder(PmPackets.RequestProfessionsC2S::new)
                .consumerMainThread(PmPackets.RequestProfessionsC2S::handle)
                .add();

        CHANNEL.messageBuilder(PmPackets.ProfessionListS2C.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(PmPackets.ProfessionListS2C::encode)
                .decoder(PmPackets.ProfessionListS2C::new)
                .consumerMainThread(PmPackets.ProfessionListS2C::handle)
                .add();

        // 管理权限状态（S2C）。**新增包只能追加在末尾**（注册顺序即协议编号）。
        CHANNEL.messageBuilder(PmPackets.AdminStateS2C.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(PmPackets.AdminStateS2C::encode)
                .decoder(PmPackets.AdminStateS2C::new)
                .consumerMainThread(PmPackets.AdminStateS2C::handle)
                .add();
    }

    /** 服务端 → 指定玩家；无通道（客户端未装模组 / 版本不匹配）时静默跳过。 */
    public static void sendTo(ServerPlayer player, Object msg) {
        if (player == null || player.connection == null) return;
        Connection connection = player.connection.connection;
        if (connection != null && CHANNEL.isRemotePresent(connection)) {
            CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), msg);
        }
    }

    /** 客户端 → 服务端。 */
    public static void sendToServer(Object msg) {
        CHANNEL.sendToServer(msg);
    }

    /** 显式探测通道（需要改变服务端行为而非仅仅丢弃消息时使用）。 */
    public static boolean hasChannel(Connection connection) {
        return connection != null && CHANNEL.isRemotePresent(connection);
    }
}
