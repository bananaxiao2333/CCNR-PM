/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.pm;

import com.ccnrcom.pm.command.AdminChatInterceptor;
import com.ccnrcom.pm.command.PmCommand;
import com.ccnrcom.pm.config.PmConfig;
import com.ccnrcom.pm.network.PmChannel;
import com.ccnrcom.pm.permission.PmPermissions;
import com.ccnrcom.pm.ticket.TicketService;
import java.nio.file.Path;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.server.ServerAboutToStartEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * CCNR-PM 入口：面向服务器的玩家管理面板。
 *
 * <p>首个功能是举报工单（{@code /a} → 工单面板）。本模组与 CCNR-RP / CCNR-Com **互相独立**，
 * 唯一的耦合点是「接管 CCNR-Com 的 {@code /a} 命令」——通过命令树后置覆盖实现，不引用对方任何类。
 */
@Mod(CCNRPMMod.MODID)
public class CCNRPMMod {

    public static final String MODID = "ccnr_pm";

    private static final Logger LOGGER = LogManager.getLogger(MODID);

    /** 运行时数据目录名（世界内）。 */
    private static final LevelResource DATA_DIR = new LevelResource("ccnr_pm");

    public CCNRPMMod() {
        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
        ModLoadingContext.get().registerConfig(ModConfig.Type.SERVER, PmConfig.SPEC);
        modBus.addListener(this::commonSetup);
        modBus.addListener(this::registerOverlays);

        // 命令接管器必须挂在 FORGE 总线上（RegisterCommandsEvent 是 forge 事件），
        // 且内部用 EventPriority.LOWEST 保证在 CCNR-Com 之后执行
        MinecraftForge.EVENT_BUS.register(new AdminChatInterceptor());
        MinecraftForge.EVENT_BUS.addListener(PmPermissions::onGatherNodes);
        MinecraftForge.EVENT_BUS.addListener(PmCommand::onRegisterCommands);
        MinecraftForge.EVENT_BUS.addListener(this::onServerAboutToStart);
        MinecraftForge.EVENT_BUS.addListener(this::onPlayerLoggedIn);
        MinecraftForge.EVENT_BUS.addListener(this::onServerStopping);
    }

    /**
     * 注册左上角的新工单通知卡片。
     *
     * <p>{@code RegisterGuiOverlaysEvent} 只在客户端触发，但监听器本身在两端都会挂上；
     * 因此用 {@code DistExecutor} 把对客户端类的引用包在双层 lambda 里，
     * 服务端永远不会类加载 {@code TicketNoticeOverlay}。
     */
    private void registerOverlays(net.minecraftforge.client.event.RegisterGuiOverlaysEvent event) {
        net.minecraftforge.fml.DistExecutor.unsafeRunWhenOn(
                net.minecraftforge.api.distmarker.Dist.CLIENT,
                () -> () ->
                        event.registerAboveAll("ticket_notice", new com.ccnrcom.pm.client.hud.TicketNoticeOverlay()));
    }

    private void commonSetup(net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent event) {
        PmChannel.register();
        LOGGER.info("[CCNR-PM] 玩家管理模组已载入");
    }

    /** 服务端启动：接入工单存储（数据库优先，失败回退文件）。 */
    private void onServerAboutToStart(ServerAboutToStartEvent event) {
        Path worldDir = event.getServer().getWorldPath(DATA_DIR);
        TicketService.init(worldDir, TicketService.defaultConfigDir());
        com.ccnrcom.pm.avatar.AvatarService.init(TicketService.defaultConfigDir());
        // 「本服见过的玩家」名录：关联玩家校验的判据来源（在线 或 见过面都算合法）
        com.ccnrcom.pm.player.KnownPlayers.init(TicketService.defaultConfigDir());
    }

    /**
     * 玩家登录时预热头像缓存。
     *
     * <p>为什么在登录时就抓，而不是等建单：抓取是异步 HTTP，若只在建单瞬间发起，
     * 首张工单往往只能等超时（或干脆没有头像）。登录时预热能让缓存里**先有货**，
     * 之后任何工单都能立刻带上真实头像。同一玩家重复登录会重新抓取并覆盖旧条目，
     * 因此换了皮肤也能跟上。
     */
    private void onPlayerLoggedIn(net.minecraftforge.event.entity.player.PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof net.minecraft.server.level.ServerPlayer player)) return;

        // 0) 登记「见过面」：关联玩家校验（在线 或 见过面）靠它，**必须在下面的头像早退之前**做。
        //    否则关掉头像抓取时，名录就不会增长，玩家会莫名其妙地「不算合法」。
        com.ccnrcom.pm.player.KnownPlayers.remember(
                player.getUUID(), player.getGameProfile().getName());

        com.ccnrcom.pm.ticket.TicketService service = com.ccnrcom.pm.ticket.TicketService.get();
        boolean admin = com.ccnrcom.pm.permission.PmPermissions.canAdminTicket(player);

        // 0.5) 下发管理权限状态：客户端凭它做"没权限就别打开管理界面"那道门。
        //      必须在下面「上线即推看板」之前——否则管理员可能在标志到达前就按 P 打开面板。
        //      这只是界面门；真正的边界是本模组每个动作都会重新判定 canAdminTicket。
        com.ccnrcom.pm.network.PmChannel.sendTo(player, new com.ccnrcom.pm.network.PmPackets.AdminStateS2C(admin));

        // 1) 上线即推看板：管理员一进来就要看到**当前已有的全部活跃工单**，
        //    而不是只能等新工单产生。这一步不能依赖头像是否抓完——卡片先出来，
        //    头像由客户端回退显示，抓完再由下面第 2 步补上。
        if (admin && service != null) {
            service.sendBoard(player);
        }

        // 2) 抓这个玩家的头像。
        //    **对所有人都抓，不能只抓管理员**：普通玩家的头像正是管理员看板卡片上要显示的东西
        //    （作为提交者或关联玩家）。而且抓取必须在玩家**在线**时做——皮肤 URL 只存在于
        //    在线 ServerPlayer 的 GameProfile 里，人一下线就再也抓不到了。
        //    成本是每次登录一次 HTTP，换取「谁的头像都在」。
        //    开关为「真正改执行路径」：关掉抓取时连 pre-> 都不发起（而不是抓完再丢弃）。
        if (!com.ccnrcom.pm.config.PmConfig.AVATAR_ENABLED.get()) return;
        com.ccnrcom.pm.avatar.AvatarService.forgetNegative(player.getUUID());
        com.ccnrcom.pm.avatar.AvatarService.PlayerRef ref = com.ccnrcom.pm.avatar.AvatarService.PlayerRef.of(player);
        if (ref == null) return;
        com.ccnrcom.pm.avatar.AvatarService.prefetch(
                        java.util.List.of(ref), com.ccnrcom.pm.config.PmConfig.AVATAR_TIMEOUT_MS.get())
                .thenAccept(ignored -> player.server.execute(() -> {
                    // 抓完后把看板推给**所有**管理员：这个玩家可能正出现在别人的卡片上，
                    // 只刷自己的那份会让其他管理员的卡片一直显示默认皮肤
                    if (service != null) service.broadcastBoard(player.server);
                }));
    }

    /** 服务端停止：排空写队列并断开连接（对称清理）。 */
    private void onServerStopping(ServerStoppingEvent event) {
        TicketService.shutdown();
        com.ccnrcom.pm.avatar.AvatarService.shutdown();
        com.ccnrcom.pm.player.KnownPlayers.shutdown();
    }
}
