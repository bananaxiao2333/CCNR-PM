/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.pm.network;

import com.ccnrcom.pm.integration.ProfessionRef;
import com.ccnrcom.pm.integration.RpBridge;
import com.ccnrcom.pm.permission.PmPermissions;
import com.ccnrcom.pm.ticket.ReportDraft;
import com.ccnrcom.pm.ticket.ReportValidator;
import com.ccnrcom.pm.ticket.TicketService;
import java.util.List;
import java.util.UUID;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;

/**
 * 服务端侧的 C2S 包处理。
 *
 * <p>放在 {@code network} 包而非 {@code client}：C2S 处理器只会运行在服务端，
 * 与客户端渲染代码放在一起会让人误以为它也可能在客户端执行。
 *
 * <p>**这里是信任边界**：{@code player} 由 Forge 从连接上下文给出（不可伪造），
 * 但包内所有字段都来自客户端，必须由 {@link TicketService#submit} 重新校验。
 */
public final class PmServerHandlers {

    private PmServerHandlers() {}

    /**
     * 权限不足时的统一回绝：顺带把 {@code admin=false} 补发给客户端。
     *
     * <p>为什么必须补发：客户端的权限镜像只在登录时下发过一次。若某个管理员在使用中被降权，
     * 他客户端上的标志仍是 {@code true}，管理界面还会继续打开（虽然一条数据都读不到）。
     * 由"每一次被拒绝的管理请求"顺手纠正，标志就能自己收敛回真值，不需要额外的轮询或定时同步。
     */
    private static void denyAdmin(ServerPlayer player) {
        com.ccnrcom.pm.network.PmChannel.sendTo(player, new com.ccnrcom.pm.network.PmPackets.AdminStateS2C(false));
    }

    /** 面板面板每页条数（与 config 的 listPageSize 一致，动作后刷新也用它）。 */
    private static int pageSize() {
        return com.ccnrcom.pm.config.PmConfig.LIST_PAGE_SIZE.get();
    }

    /**
     * 面板请求一页工单。
     *
     * <p>**权限在这里重新判定**：客户端能打开面板不代表它有权读工单列表——
     * 面板只是界面，权限由服务端说了算（客户端被改造过也拿不到数据）。
     */
    public static void onRequestTickets(ServerPlayer player, String filter, int page) {
        if (!PmPermissions.canAdminTicket(player)) {
            denyAdmin(player);
            return;
        }
        TicketService service = TicketService.get();
        if (service == null) return;
        PmChannel.sendTo(
                player,
                new PmPackets.TicketListS2C(service.page(
                                filter,
                                page,
                                pageSize(),
                                player.getGameProfile().getName())
                        .toJsonString()));
    }

    /** 面板动作（认领/办结/驳回）。先鉴权，再执行，成功则回推一份新数据。 */
    public static void onTicketAction(ServerPlayer player, String action, String ticketId, String note) {
        if (!PmPermissions.canAdminTicket(player)) {
            denyAdmin(player);
            return;
        }
        TicketService service = TicketService.get();
        if (service == null) return;

        TicketService.ActionOutcome outcome = service.act(player, action, ticketId, note);
        String messageKey =
                switch (outcome) {
                    case OK -> "";
                    case NOT_FOUND -> "ccnr_pm.command.ticket.not_found";
                    case BAD_STATE -> "ccnr_pm.command.ticket.resolve_failed";
                    case UNKNOWN_ACTION -> "ccnr_pm.panel.err.unknown_action";
                };
        PmChannel.sendTo(player, new PmPackets.AdminResultS2C(outcome == TicketService.ActionOutcome.OK, messageKey));

        // 动作成功后立刻回推权威数据：客户端不自己改本地列表，
        // 否则「本地以为办结了、服务端其实没办成」这类不一致就无从察觉
        if (outcome == TicketService.ActionOutcome.OK) {
            PmChannel.sendTo(
                    player,
                    new PmPackets.TicketListS2C(service.page(
                                    "all",
                                    1,
                                    pageSize(),
                                    player.getGameProfile().getName())
                            .toJsonString()));
        }
    }

    // ------------------------------------------------------------------
    // 对局（当局状态 / 当局事件流 / 对局管理）
    // ------------------------------------------------------------------

    /**
     * 收束对局时写进上报的理由。
     *
     * <p><b>刻意是一个服务端常量，不接受客户端传入。</b> CCNR-RP 把理由拼进事件上报的
     * JSON 载荷时用的是字符串拼接（{@code "{\"reason\":\"" + reason + "\"}"}），
     * 因此一段来自客户端的自由文本只要带一个引号就能把存进数据库的载荷弄成非法 JSON。
     * 面板上本来也没有理由输入框——留一个「能传任意字符串」的口子只有坏处。
     */
    private static final String MATCH_END_REASON = "pm";

    /** 对局动作失败的提示键（成功时不用任何键）。 */
    private static final String MATCH_ACTION_FAILED = "ccnr_pm.panel.err.match_action_failed";

    /**
     * 面板请求一份对局快照。
     *
     * <p><b>权限在这里重新判定</b>：与工单列表同理，能打开面板不代表有权读对局状态或下指令。
     *
     * <p>CCNR-RP 未安装时照样回一份 {@code available=false} 的**正常载荷**：
     * 界面据此显示「未安装 CCNR-RP」并禁用动作按钮，而不是停在「正在读取……」。
     */
    public static void onRequestMatchState(ServerPlayer player) {
        if (!PmPermissions.canAdminTicket(player)) {
            denyAdmin(player);
            return;
        }
        PmChannel.sendTo(player, new PmPackets.MatchStateS2C(RpBridge.matchSnapshot()));
    }

    /**
     * 对局管理动作（切幕 / 触发事件 / 结束事件 / 收束对局 / 重开一局 / 播结束动画）。
     *
     * <h2>信任边界</h2>
     * <ol>
     *   <li>权限：{@code canAdminTicket} 重新判定（改造过的客户端发不动）；</li>
     *   <li>动作：未知 id 明确回绝并提示，**不静默忽略**（否则「点了没反应」无从定位）；</li>
     *   <li>目标：{@code arg} 只是从快照里复制的线索，**不在这里校验**——幕存不存在、
     *       事件现在能不能触发，都是 CCNR-RP 自己的规则，由它的入口回绝并返回 false。
     *       在这里再判一次就是第二份校验，对方一改就漂移；</li>
     *   <li>动作 → 对方入口的映射是**白名单**（{@link RpBridge#matchAction}），
     *       且不传递任何客户端自由文本（见 {@link #MATCH_END_REASON}）。</li>
     * </ol>
     *
     * <p>动作后立刻回推一份新快照：客户端**从不自己改本地对局状态**，
     * 否则「本地以为切幕了、服务端其实拒绝了」这类不一致就无从察觉。
     */
    public static void onMatchAction(ServerPlayer player, String action, String arg) {
        if (!PmPermissions.canAdminTicket(player)) {
            denyAdmin(player);
            return;
        }
        if (!RpBridge.available()) {
            PmChannel.sendTo(player, new PmPackets.MatchResultS2C(false, "ccnr_pm.panel.err.rp_absent"));
            return;
        }

        String act = action == null ? "" : action;
        String target = arg == null ? "" : arg;
        // 幕的 target 为空 = 切下一幕（CCNR-RP 自己的 switchPhase("") 语义，PM 只透传）
        boolean ok;
        switch (act) {
            case PmPackets.ACT_MATCH_PHASE -> ok = RpBridge.matchAction(RpBridge.RP_SWITCH_PHASE, target);
            case PmPackets.ACT_MATCH_EVENT_ON -> ok = RpBridge.matchAction(RpBridge.RP_TRIGGER_EVENT, target);
            case PmPackets.ACT_MATCH_EVENT_OFF -> ok = RpBridge.matchAction(RpBridge.RP_END_EVENT, target);
            case PmPackets.ACT_MATCH_END -> ok = RpBridge.matchAction(RpBridge.RP_END_MATCH, MATCH_END_REASON);
            case PmPackets.ACT_MATCH_RESET -> ok = RpBridge.matchAction(RpBridge.RP_CLEAR_EVENTS, null);
            case PmPackets.ACT_MATCH_GAME_OVER -> ok = RpBridge.matchAction(RpBridge.RP_GAME_OVER, null);
            default -> {
                PmChannel.sendTo(player, new PmPackets.MatchResultS2C(false, "ccnr_pm.panel.err.unknown_action"));
                return;
            }
        }
        PmChannel.sendTo(player, new PmPackets.MatchResultS2C(ok, ok ? "" : MATCH_ACTION_FAILED));
        onRequestMatchState(player);
    }

    // ------------------------------------------------------------------
    // 看板头像上的玩家动作
    // ------------------------------------------------------------------

    /**
     * 看板头像上的玩家动作（传送 / 刷出 / 送回阴间）。
     *
     * <h2>信任边界</h2>
     * 与工单动作一样，这里**一切都重新判定**：
     * <ol>
     *   <li>权限：{@code canAdminTicket}——能打开看板不等于有权传送别人（改造过的客户端也发不动）；</li>
     *   <li>目标：包里的 uuid/名字只是线索，必须在服务端玩家列表里**重新解析**成在线玩家。
     *       离线、改名、伪造的名字一律解析失败并回绝——绝不用客户端的自述去动手；</li>
     *   <li>动作：未知 id 直接回绝，不静默忽略（否则「点了没反应」无从定位）。</li>
     * </ol>
     *
     * <p>结果通过系统消息回给管理员本人（{@code Component.translatable} 在客户端按玩家语言渲染），
     * 不额外开一条 S2C 通道：这类一次性反馈不值得再占一个协议号。
     */
    public static void onPlayerAction(
            ServerPlayer admin, String action, String targetUuid, String targetName, String arg) {
        if (!PmPermissions.canAdminTicket(admin)) return;

        String act = action == null ? "" : action;
        if (PmPackets.ACT_TP_TO.equals(act)) {
            // 「传送到该玩家」动的是**管理员自己**，所以目标解析的是「要去哪里」而不是「动谁」
            ServerPlayer destination = resolvePlayer(admin, targetUuid, targetName);
            if (destination == null) {
                offline(admin, targetName, targetUuid);
                return;
            }
            teleport(admin, destination);
            done(admin, "ccnr_pm.player.ok.tp_to", destination.getGameProfile().getName());
            return;
        }

        ServerPlayer target = resolvePlayer(admin, targetUuid, targetName);
        if (target == null) {
            offline(admin, targetName, targetUuid);
            return;
        }
        String name = target.getGameProfile().getName();

        switch (act) {
            case PmPackets.ACT_TP_HERE -> {
                teleport(target, admin);
                done(admin, "ccnr_pm.player.ok.tp_here", name);
            }
            case PmPackets.ACT_SPAWN -> spawn(admin, target, arg, false);
            case PmPackets.ACT_SPAWN_TP -> spawn(admin, target, arg, true);
            case PmPackets.ACT_OBSERVER -> observer(admin, target);
            default -> admin.sendSystemMessage(Component.translatable("ccnr_pm.player.err.unknown_action"));
        }
    }

    /** 可选角色列表：只有能从服务端真的部署时才说「有 RP」。 */
    public static void onRequestProfessions(ServerPlayer player) {
        if (!PmPermissions.canAdminTicket(player)) {
            denyAdmin(player);
            return;
        }
        boolean available = RpBridge.available();
        PmChannel.sendTo(
                player,
                new PmPackets.ProfessionListS2C(
                        available, ProfessionRef.toJson(available ? RpBridge.professions() : List.of())));
    }

    /**
     * 刷出玩家：装了 CCNR-RP 就按选定的角色走对方唯一的部署入口（管理员强制，无视冷却与等级）；
     * 没装则退回原版语义——切成冒险模式。
     */
    private static void spawn(ServerPlayer admin, ServerPlayer target, String role, boolean teleportHere) {
        String name = target.getGameProfile().getName();
        if (RpBridge.available()) {
            if (role == null || role.isBlank()) {
                admin.sendSystemMessage(Component.translatable("ccnr_pm.player.err.role_required"));
                return;
            }
            if (!RpBridge.forceDeploy(target, role)) {
                // 失败的常见原因是**目标客户端素材还没同步完**（CCNR-RP 的设计：
                // 此时部署会让客户端显示错误装备），对方已给该玩家发提示，这里只告诉管理员没成
                admin.sendSystemMessage(Component.translatable("ccnr_pm.player.err.spawn_failed", name));
                return;
            }
            done(admin, "ccnr_pm.player.ok.spawn", name);
        } else {
            target.setGameMode(GameType.ADVENTURE);
            done(admin, "ccnr_pm.player.ok.adventure", name);
        }
        if (teleportHere) teleport(target, admin);
    }

    /**
     * 送回阴间：装了 CCNR-RP 就走对方唯一的退场核心（观察者状态 + 清背包 + 卸阵营属性，不生成遗体）；
     * 没装则退回原版语义——单纯切换旁观模式。
     */
    private static void observer(ServerPlayer admin, ServerPlayer target) {
        String name = target.getGameProfile().getName();
        if (RpBridge.available() && !RpBridge.retireToObserver(target)) {
            admin.sendSystemMessage(Component.translatable("ccnr_pm.player.err.observer_failed", name));
            return;
        }
        // 对方的轮询（2 秒）也会把观察者切成旁观；这里立即切一次，免得管理员以为没生效。
        // 最终状态与对方一致，不是第二套规则。
        target.setGameMode(GameType.SPECTATOR);
        done(admin, "ccnr_pm.player.ok.observer", name);
    }

    /**
     * 把客户端给的目标描述解析成**服务端认识的在线玩家**。
     *
     * <p>先按 UUID（精确），再按名字（大小写不敏感，与原版一致）。两者都不是身份声明，
     * 只是查找线索：查不到就是回绝，而不是「相信客户端说的那个人存在」。
     */
    private static ServerPlayer resolvePlayer(ServerPlayer admin, String uuid, String name) {
        if (admin == null || admin.server == null) return null;
        if (uuid != null && !uuid.isBlank()) {
            try {
                ServerPlayer byUuid = admin.server.getPlayerList().getPlayer(UUID.fromString(uuid));
                if (byUuid != null) return byUuid;
            } catch (IllegalArgumentException ignored) {
                // uuid 是客户端给的，格式不对就退回按名字查
            }
        }
        String n = name == null ? "" : name.strip();
        if (n.isEmpty()) return null;
        return admin.server.getPlayerList().getPlayerByName(n);
    }

    /** 把 {@code mover} 传送到 {@code anchor} 所在位置（跨维度由原版传送链路处理）。 */
    private static void teleport(ServerPlayer mover, ServerPlayer anchor) {
        if (mover == null || anchor == null) return;
        ServerLevel level = (ServerLevel) anchor.level();
        mover.teleportTo(level, anchor.getX(), anchor.getY(), anchor.getZ(), mover.getYRot(), mover.getXRot());
    }

    private static void offline(ServerPlayer admin, String name, String uuid) {
        String label = name == null || name.isBlank() ? uuid : name;
        admin.sendSystemMessage(Component.translatable("ccnr_pm.player.err.offline", label == null ? "" : label));
    }

    private static void done(ServerPlayer admin, String key, String name) {
        admin.sendSystemMessage(Component.translatable(key, name));
    }

    /** 工单提交：一律以服务端校验结果为准，并把结论回发客户端。 */
    public static void onSubmitTicket(ServerPlayer player, ReportDraft draft) {
        TicketService service = TicketService.get();
        if (service == null) {
            PmChannel.sendTo(
                    player,
                    PmPackets.SubmitResultS2C.of(List.of(ReportValidator.Error.of(TicketService.ERR_NOT_READY))));
            return;
        }
        TicketService.SubmitResult result = service.submit(player, draft);
        if (result.ok() && result.ticket() != null) {
            PmChannel.sendTo(
                    player, PmPackets.SubmitResultS2C.success(result.ticket().label()));
        } else {
            PmChannel.sendTo(player, PmPackets.SubmitResultS2C.of(result.errors()));
        }
    }
}
