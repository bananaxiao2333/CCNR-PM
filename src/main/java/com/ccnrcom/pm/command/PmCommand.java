/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.pm.command;

import com.ccnrcom.pm.config.PmConfig;
import com.ccnrcom.pm.network.PmChannel;
import com.ccnrcom.pm.permission.PmPermissions;
import com.ccnrcom.pm.ticket.CategoryRegistry;
import com.ccnrcom.pm.ticket.Ticket;
import com.ccnrcom.pm.ticket.TicketId;
import com.ccnrcom.pm.ticket.TicketService;
import com.ccnrcom.pm.ticket.TicketStatus;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.tree.LiteralCommandNode;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.RegisterCommandsEvent;

/**
 * {@code /pm} 命令树：举报面板入口 + 工单管理 + 存储诊断。
 *
 * <p>为什么把面板入口也做成命令：{@code /a} 那条路径依赖 CCNR-Com 存在。
 * 给玩家一个始终可用的 {@code /pm report}，功能就不会因为「少装了一个模组」而彻底消失。
 *
 * <p>管理子命令一律重新做权限校验（{@link PmPermissions#canAdminTicket}），
 * 与网络包的处理方式一致——命令树的 {@code requires} 只是「可见性」，不是「授权」。
 */
public final class PmCommand {

    /**
     * 独立入口的命令名。
     *
     * <p>为什么在 {@code /pm report} 之外还要一个顶级命令：玩家要举报时不该先记住
     * 「本模组的命令前缀是 /pm」——「/report」是任何玩家第一次遇到问题就会本能去敲的东西。
     * 顶级命令也省掉了一层 Tab 补全。
     */
    public static final String STANDALONE_REPORT = "report";

    private PmCommand() {}

    /** Forge 事件适配：命令树每次重建（含数据包重载）都会走这里。 */
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        register(event.getDispatcher());
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        LiteralCommandNode<CommandSourceStack> pm = dispatcher.register(Commands.literal("pm")
                .executes(ctx -> help(ctx.getSource()))
                .then(Commands.literal("help").executes(ctx -> help(ctx.getSource())))
                .then(Commands.literal("report").executes(ctx -> report(ctx.getSource())))
                .then(Commands.literal("panel").requires(adminOnly()).executes(ctx -> panel(ctx.getSource())))
                .then(statusNode())
                .then(reloadNode())
                .then(ticketNode()));
        dispatcher.register(Commands.literal("ccnrpm").redirect(pm));
        // 独立入口：不必先打 /pm，直接 /report 就能开工单面板
        dispatcher.register(Commands.literal(STANDALONE_REPORT).executes(ctx -> report(ctx.getSource())));
    }

    // 子命令各自一个构建方法：Brigadier 的链式构建层层嵌套，一次写成一大坨极难数清括号，
    // 拆开之后每个节点的层级一目了然，也便于单独调整某个子命令。

    private static LiteralArgumentBuilder<CommandSourceStack> statusNode() {
        return Commands.literal("status").requires(adminOnly()).executes(ctx -> status(ctx.getSource()));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> reloadNode() {
        return Commands.literal("reload").requires(adminOnly()).executes(ctx -> reload(ctx.getSource()));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> ticketNode() {
        return Commands.literal("ticket")
                .requires(adminOnly())
                .then(listNode())
                .then(showNode())
                .then(claimNode())
                .then(closeNode())
                .then(rejectNode())
                .then(ignoreNode())
                .then(unignoreNode());
    }

    private static LiteralArgumentBuilder<CommandSourceStack> listNode() {
        return Commands.literal("list")
                .executes(ctx -> list(ctx.getSource(), "all", 1))
                .then(Commands.argument("status", StringArgumentType.word())
                        .executes(ctx -> list(ctx.getSource(), StringArgumentType.getString(ctx, "status"), 1))
                        .then(Commands.argument("page", IntegerArgumentType.integer(1))
                                .executes(ctx -> list(
                                        ctx.getSource(),
                                        StringArgumentType.getString(ctx, "status"),
                                        IntegerArgumentType.getInteger(ctx, "page")))));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> showNode() {
        return Commands.literal("show")
                .then(Commands.argument("ticket", StringArgumentType.word())
                        .executes(ctx -> show(ctx.getSource(), StringArgumentType.getString(ctx, "ticket"))));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> claimNode() {
        return Commands.literal("claim")
                .then(Commands.argument("ticket", StringArgumentType.word())
                        .executes(ctx ->
                                transition(ctx.getSource(), StringArgumentType.getString(ctx, "ticket"), null, null)));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> closeNode() {
        return Commands.literal("close")
                .then(Commands.argument("ticket", StringArgumentType.word())
                        .executes(ctx -> transition(
                                ctx.getSource(),
                                StringArgumentType.getString(ctx, "ticket"),
                                TicketStatus.CLOSED,
                                null))
                        .then(Commands.argument("note", StringArgumentType.greedyString())
                                .executes(ctx -> transition(
                                        ctx.getSource(),
                                        StringArgumentType.getString(ctx, "ticket"),
                                        TicketStatus.CLOSED,
                                        StringArgumentType.getString(ctx, "note")))));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> rejectNode() {
        return Commands.literal("reject")
                .then(Commands.argument("ticket", StringArgumentType.word())
                        .executes(ctx -> transition(
                                ctx.getSource(),
                                StringArgumentType.getString(ctx, "ticket"),
                                TicketStatus.REJECTED,
                                null))
                        .then(Commands.argument("note", StringArgumentType.greedyString())
                                .executes(ctx -> transition(
                                        ctx.getSource(),
                                        StringArgumentType.getString(ctx, "ticket"),
                                        TicketStatus.REJECTED,
                                        StringArgumentType.getString(ctx, "note")))));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> ignoreNode() {
        return Commands.literal("ignore")
                .then(Commands.argument("ticket", StringArgumentType.word())
                        .executes(
                                ctx -> setIgnored(ctx.getSource(), StringArgumentType.getString(ctx, "ticket"), true)));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> unignoreNode() {
        return Commands.literal("unignore")
                .then(Commands.argument("ticket", StringArgumentType.word())
                        .executes(ctx ->
                                setIgnored(ctx.getSource(), StringArgumentType.getString(ctx, "ticket"), false)));
    }

    /**
     * 忽略 / 取消忽略某工单。
     *
     * <p>**为什么必须有「取消忽略」**：忽略是「这张卡片不再出现在我的看板上」，
     * 若没有解除入口，一次误点就等于这位管理员**永远**看不到那张工单——功能不闭环。
     * 因此忽略与取消忽略成对提供，且都很便宜。
     *
     * <p>忽略是**按管理员隔离**的：只影响调用者自己的看板，不改变工单状态，
     * 也不影响其他管理员。
     */
    private static int setIgnored(CommandSourceStack source, String query, boolean ignored) {
        Ticket t = resolveTicket(source, query);
        if (t == null) return 0;
        ServerPlayer admin = source.getPlayer();
        if (admin == null) {
            source.sendFailure(Component.translatable("ccnr_pm.command.player_only"));
            return 0;
        }
        boolean ok = TicketService.get().changeIgnore(admin, t.id(), ignored);
        if (!ok) {
            // 状态没变（重复忽略/重复取消）不算失败，只是无事发生
            source.sendSuccess(
                    () -> Component.translatable(
                            ignored ? "ccnr_pm.command.ticket.ignored" : "ccnr_pm.command.ticket.unignored", t.label()),
                    true);
            return 1;
        }
        source.sendSuccess(
                () -> Component.translatable(
                        ignored ? "ccnr_pm.command.ticket.ignored" : "ccnr_pm.command.ticket.unignored", t.label()),
                true);
        return 1;
    }

    /**
     * 解析工单标识：接受完整 UUID 或短号前缀（忽略大小写与连字符）。
     *
     * <p>前缀命中多张时**拒绝执行并提示补更多字符**，绝不替用户挑一张——
     * 替用户猜等于随机对某张工单执行认领/办结，那是不可撤销的操作。
     *
     * @return 命中的工单；任何失败情形都已向命令源发出提示并返回 {@code null}
     */
    private static Ticket resolveTicket(CommandSourceStack source, String query) {
        TicketService service = TicketService.get();
        if (service == null) {
            source.sendFailure(Component.translatable("ccnr_pm.report.err.not_ready"));
            return null;
        }
        if (!TicketId.isValidQuery(query)) {
            source.sendFailure(
                    Component.translatable("ccnr_pm.command.ticket.bad_id", Integer.toString(TicketId.MIN_QUERY_LEN)));
            return null;
        }
        List<Ticket> found = service.byIdPrefix(query, 2);
        if (found.isEmpty()) {
            source.sendFailure(
                    Component.translatable("ccnr_pm.command.ticket.not_found", "#" + TicketId.shortId(query)));
            return null;
        }
        if (found.size() > 1) {
            source.sendFailure(Component.translatable("ccnr_pm.command.ticket.ambiguous", query));
            return null;
        }
        return found.get(0);
    }

    /** 管理判据：在线玩家走权限节点，控制台按 OP 等级。 */
    private static Predicate<CommandSourceStack> adminOnly() {
        return src -> {
            ServerPlayer p = src.getPlayer();
            return p != null ? PmPermissions.canAdminTicket(p) : src.hasPermission(2);
        };
    }

    private static int help(CommandSourceStack source) {
        for (String key : List.of(
                "ccnr_pm.command.help",
                "ccnr_pm.command.usage.report",
                "ccnr_pm.command.usage.status",
                "ccnr_pm.command.usage.panel",
                "ccnr_pm.command.usage.list",
                "ccnr_pm.command.usage.show",
                "ccnr_pm.command.usage.claim",
                "ccnr_pm.command.usage.close",
                "ccnr_pm.command.usage.reject",
                "ccnr_pm.command.usage.ignore",
                "ccnr_pm.command.usage.unignore",
                "ccnr_pm.command.usage.reload")) {
            source.sendSuccess(() -> Component.translatable(key), false);
        }
        return 1;
    }

    /** 打开举报面板（任何玩家可用）。 */
    private static int report(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.translatable("ccnr_pm.command.player_only"));
            return 0;
        }
        TicketService service = TicketService.get();
        if (service == null) {
            source.sendFailure(Component.translatable("ccnr_pm.report.err.not_ready"));
            return 0;
        }
        service.sendOpenPanel(player, "");
        source.sendSuccess(() -> Component.translatable("ccnr_pm.report.opened_empty", "/pm report"), false);
        return 1;
    }

    /**
     * 打开管理面板。
     *
     * <p>桌面端不用按键绑定也能开面板：服务端脚本、后台、或按键冲突时都还有这条路。
     */
    private static int panel(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.translatable("ccnr_pm.command.player_only"));
            return 0;
        }
        if (!PmPermissions.canAdminTicket(player)) {
            source.sendFailure(Component.translatable("ccnr_pm.command.no_permission"));
            return 0;
        }
        PmChannel.sendTo(player, new com.ccnrcom.pm.network.PmPackets.OpenAdminPanelS2C());
        return 1;
    }

    private static int status(CommandSourceStack source) {
        TicketService service = TicketService.get();
        if (service == null) {
            source.sendFailure(Component.translatable("ccnr_pm.report.err.not_ready"));
            return 0;
        }
        source.sendSuccess(() -> Component.translatable("ccnr_pm.command.status.header"), false);
        source.sendSuccess(
                () -> Component.translatable("ccnr_pm.command.status.backend", service.backendName()), false);
        source.sendSuccess(
                () -> Component.translatable(
                        "ccnr_pm.command.status.database",
                        service.databaseEnabled() ? "ON" : "OFF",
                        service.ping() ? "OK" : "FAIL"),
                false);
        source.sendSuccess(
                () -> Component.translatable(
                        "ccnr_pm.command.status.counts",
                        service.count(null),
                        service.count(TicketStatus.OPEN),
                        service.count(TicketStatus.CLAIMED)),
                false);
        source.sendSuccess(
                () -> Component.translatable(
                        "ccnr_pm.command.status.categories",
                        CategoryRegistry.active().all().size(),
                        CategoryRegistry.active().enabled().size()),
                false);
        source.sendSuccess(
                () -> Component.translatable(
                        "ccnr_pm.command.status.permission",
                        PmPermissions.adminChatNodeState().orElse("-")),
                false);
        // 头像诊断：用户反馈「拿不到真实头像」时，这一行直接告诉你缓存里有没有货、有没有人抓不到
        source.sendSuccess(
                () -> Component.translatable(
                        "ccnr_pm.command.status.avatar",
                        com.ccnrcom.pm.avatar.AvatarService.size(),
                        com.ccnrcom.pm.avatar.AvatarService.negativeCount(),
                        com.ccnrcom.pm.config.PmConfig.AVATAR_MOJANG_LOOKUP.get() ? "ON" : "OFF",
                        String.join(" ", com.ccnrcom.pm.avatar.AvatarService.describe())),
                false);
        source.sendSuccess(
                () -> Component.translatable(
                        "ccnr_pm.command.status.known_players", com.ccnrcom.pm.player.KnownPlayers.size()),
                false);
        return 1;
    }

    private static int reload(CommandSourceStack source) {
        TicketService.loadCategories(TicketService.defaultConfigDir());
        int n = CategoryRegistry.active().enabled().size();
        source.sendSuccess(() -> Component.translatable("ccnr_pm.command.reloaded", n), false);
        return 1;
    }

    private static int list(CommandSourceStack source, String statusArg, int page) {
        TicketService service = TicketService.get();
        if (service == null) {
            source.sendFailure(Component.translatable("ccnr_pm.report.err.not_ready"));
            return 0;
        }
        TicketStatus filter = "all".equalsIgnoreCase(statusArg) ? null : TicketStatus.parse(statusArg);
        int pageSize = PmConfig.LIST_PAGE_SIZE.get();
        int total = service.count(filter);
        int pages = Math.max(1, (total + pageSize - 1) / pageSize);
        int current = Math.min(page, pages);
        List<Ticket> rows = service.list(filter, pageSize, (current - 1) * pageSize);

        source.sendSuccess(
                () -> Component.translatable(
                        "ccnr_pm.command.ticket.list.header",
                        statusArg.toLowerCase(Locale.ROOT),
                        current,
                        pages,
                        total),
                false);
        if (rows.isEmpty()) {
            source.sendSuccess(() -> Component.translatable("ccnr_pm.command.ticket.list.empty"), false);
            return 1;
        }
        for (Ticket t : rows) {
            source.sendSuccess(
                    () -> Component.translatable(
                            "ccnr_pm.command.ticket.row",
                            t.label(),
                            statusName(t.status()),
                            t.reporterName(),
                            // 关联玩家是选填的：没有关联时显示占位符，而不是留一个空箭头
                            t.targets().isEmpty() ? "-" : t.targetLabel()),
                    false);
        }
        return 1;
    }

    private static int show(CommandSourceStack source, String query) {
        Ticket t = resolveTicket(source, query);
        if (t == null) return 0;
        source.sendSuccess(
                () -> Component.translatable("ccnr_pm.command.ticket.show.header", t.label(), statusName(t.status())),
                false);
        source.sendSuccess(
                () -> Component.translatable("ccnr_pm.command.ticket.show.reporter", t.reporterName()), false);
        // 提交时间：管理员判断「这条是不是刚发生的」最需要的信息（用户反馈过看不到）
        source.sendSuccess(
                () -> Component.translatable(
                        "ccnr_pm.command.ticket.show.created", com.ccnrcom.pm.util.TimeText.format(t.createdAt())),
                false);
        source.sendSuccess(
                () -> Component.translatable(
                        "ccnr_pm.command.ticket.show.targets",
                        t.targets().isEmpty() ? Component.translatable("ccnr_pm.gui.none") : t.targetLabel()),
                false);
        source.sendSuccess(
                () -> Component.translatable(
                        "ccnr_pm.command.ticket.show.category",
                        Component.translatable("ccnr_pm.category." + t.categoryId() + ".name")),
                false);
        source.sendSuccess(() -> Component.translatable("ccnr_pm.command.ticket.show.content", t.content()), false);
        if (!t.detail().isBlank()) {
            source.sendSuccess(() -> Component.translatable("ccnr_pm.command.ticket.show.detail", t.detail()), false);
        }
        if (t.handlerName() != null) {
            source.sendSuccess(
                    () -> Component.translatable(
                            "ccnr_pm.command.ticket.show.handler",
                            t.handlerName(),
                            t.handleNote() == null ? "-" : t.handleNote()),
                    false);
        }
        return 1;
    }

    /** 认领 / 办结 / 驳回。 */
    private static int transition(CommandSourceStack source, String query, TicketStatus target, String note) {
        Ticket t = resolveTicket(source, query);
        if (t == null) return 0;
        TicketService service = TicketService.get();
        ServerPlayer admin = source.getPlayer();
        String label = t.label();

        boolean ok;
        if (target == null) {
            ok = service.claim(admin, t);
            if (!ok) {
                source.sendFailure(Component.translatable("ccnr_pm.command.ticket.claim_failed", label));
                return 0;
            }
            source.sendSuccess(() -> Component.translatable("ccnr_pm.command.ticket.claimed", label), true);
            return 1;
        }

        ok = service.resolve(admin, t, target, note == null ? "" : note);
        if (!ok) {
            source.sendFailure(Component.translatable("ccnr_pm.command.ticket.resolve_failed", label));
            return 0;
        }
        String key =
                target == TicketStatus.CLOSED ? "ccnr_pm.command.ticket.closed" : "ccnr_pm.command.ticket.rejected";
        source.sendSuccess(() -> Component.translatable(key, label), true);

        // 让举报人知道结果：否则玩家永远不知道自己的举报有没有被处理
        notifyReporter(source, t, admin, target, label, note);
        return 1;
    }

    /**
     * 把处理结果告知举报人（仅当他仍在线）。
     *
     * <p>受理人可能是控制台（{@code admin == null}），此时用 {@code source} 的文本名代替，
     * 避免为了拿一个名字而解引用空对象。
     */
    private static void notifyReporter(
            CommandSourceStack source, Ticket t, ServerPlayer admin, TicketStatus target, String label, String note) {
        ServerPlayer reporter = admin != null && admin.server != null
                ? admin.server.getPlayerList().getPlayer(t.reporterUuid())
                : null;
        if (reporter == null) return;

        String by = admin != null ? admin.getGameProfile().getName() : source.getTextName();
        String noteText = note == null || note.isBlank() ? "-" : note;
        reporter.sendSystemMessage(Component.translatable(
                        target == TicketStatus.CLOSED ? "ccnr_pm.ticket.resolved" : "ccnr_pm.ticket.rejected",
                        label,
                        noteText)
                .withStyle(target == TicketStatus.CLOSED ? ChatFormatting.GREEN : ChatFormatting.YELLOW));
        reporter.sendSystemMessage(
                Component.translatable("ccnr_pm.ticket.handled_by", by).withStyle(ChatFormatting.GRAY));
    }

    /**
     * 状态显示名（本地化）+ **状态色**。
     *
     * <p>用户反馈「各种状态不好分辨」，因此聊天里的状态词也上色：
     * 待处理＝红、处理中＝白、已办结＝灰、已驳回＝暗灰——与界面里的 {@code PmTheme.statusColor} 同一套语义。
     *
     * <p>为什么这里再写一份映射：{@code PmTheme} 位于 {@code client} 包内，
     * 而服务端路径**不得引用 client 类**（docs/00 §8 模块边界）。原版 {@code ChatFormatting} 两端都能用，
     * 所以聊天色与命令一起放这里，界面色继续由 {@code PmTheme} 承担。
     */
    private static Component statusName(TicketStatus status) {
        return Component.translatable("ccnr_pm.status." + status.id()).withStyle(statusFormat(status));
    }

    /** 状态 → 原版聊天色（服务端可安全使用，不能引用 client 包的 {@code PmTheme}）。 */
    private static ChatFormatting statusFormat(TicketStatus status) {
        return switch (status) {
            case OPEN -> ChatFormatting.RED;
            case CLAIMED -> ChatFormatting.WHITE;
            case CLOSED -> ChatFormatting.GRAY;
            case REJECTED -> ChatFormatting.DARK_GRAY;
        };
    }
}
