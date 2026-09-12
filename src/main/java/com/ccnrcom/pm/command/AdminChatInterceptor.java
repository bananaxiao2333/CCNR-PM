/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.pm.command;

import com.ccnrcom.pm.permission.PmPermissions;
import com.ccnrcom.pm.ticket.TicketService;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.tree.CommandNode;
import com.mojang.brigadier.tree.LiteralCommandNode;
import com.mojang.brigadier.tree.RootCommandNode;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * 把 CCNR-Com 的「管理员发送消息」命令 {@code /a}（别名 {@code /admin}）改造成双通道入口。
 *
 * <h2>为什么需要拦截，而不是改 CCNR-Com</h2>
 * 本模组独立于 CCNR-Com（不产生编译期依赖）。{@code /a} 由 CCNR-Com 注册，且带
 * {@code .requires(Permissions::canAdmin)}——普通玩家执行它时 Brigadier 直接判定「命令不存在」，
 * 玩家看到的是一句权限回绝。需求要的是：**不再回绝，而是把这条消息当作举报理由，直接弹出工单面板。**
 *
 * <h2>怎么做到「不改对方代码」</h2>
 * Forge 在 {@link RegisterCommandsEvent} 里把命令树交给各模组填充。Brigadier 的
 * {@code addChild} 以名字为键覆盖同名子节点，因此**在对方之后**再注册一个同名 {@code /a} 即可取而代之。
 * 时序靠 {@link EventPriority#LOWEST} 保证（CCNR-Com 用默认 NORMAL 注册，NORMAL 先于 LOWEST 执行）。
 *
 * <p>管理员的原有行为**一点都不能少**：我们拿到原 {@code /a} 节点里 {@code message} 子节点的执行器，
 * 管理员路径直接把命令上下文转交给它执行。因此广播范围、长度校验、失败提示全部沿用原实现，
 * 我们只新增了「非管理员 → 开工单面板」这一条分支。
 *
 * <h2>边界</h2>
 * <ul>
 *   <li>CCNR-Com 未安装时 {@code /a} 不存在，我们**不会**凭空创建它（否则会与别的模组抢同名命令）；
 *       此时只记日志，举报面板仍可由 {@code /pm report} 手动打开。</li>
 *   <li>数据包重载会整树重建命令，本处理器会跟着重跑一次并重新接管。</li>
 * </ul>
 */
public final class AdminChatInterceptor {

    private static final Logger LOGGER = LogManager.getLogger("ccnr_pm");

    /** 被接管的主命令字面量。 */
    private static final String ROOT_LITERAL = "a";

    /** CCNR-Com 为 {@code /a} 注册的别名。 */
    private static final String ALIAS_LITERAL = "admin";

    /** 消息参数名，必须与原命令一致——管理员路径转发时靠它取参数。 */
    private static final String ARG_MESSAGE = "message";

    /** 文本来源标记：面板提示「内容已从 /a 消息预填」。 */
    private static final String SOURCE_COMMAND = "/a";

    /** 我们安装的节点；用于防止同一次命令树构建里重复接管。 */
    private static volatile LiteralCommandNode<CommandSourceStack> installed;

    /** 原 {@code /a <消息>} 的执行器（管理员路径转发用）；CCNR-Com 缺席时为 null。 */
    private static volatile Command<CommandSourceStack> originalMessageCommand;

    /** 原裸 {@code /a} 的执行器（管理员看到用法提示）。 */
    private static volatile Command<CommandSourceStack> originalBareCommand;

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onRegisterCommands(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        RootCommandNode<CommandSourceStack> root = dispatcher.getRoot();

        CommandNode<CommandSourceStack> existing = root.getChild(ROOT_LITERAL);
        if (existing == null) {
            LOGGER.info("[CCNR-PM] 未发现 /{} 命令（通常表示 CCNR-Com 未安装）：命令接管未生效，举报面板可用 /pm report 打开", ROOT_LITERAL);
            installed = null;
            originalMessageCommand = null;
            originalBareCommand = null;
            return;
        }
        if (existing == installed) {
            return; // 已经接管过这次构建的命令树
        }

        originalMessageCommand = commandOf(existing.getChild(ARG_MESSAGE));
        originalBareCommand = commandOf(existing);

        LiteralCommandNode<CommandSourceStack> ours = Commands.literal(ROOT_LITERAL)
                .executes(ctx -> onBare(ctx))
                .then(Commands.argument(ARG_MESSAGE, StringArgumentType.greedyString())
                        .executes(ctx -> onMessage(ctx, StringArgumentType.getString(ctx, ARG_MESSAGE))))
                .build();

        // 保留原节点的其它子命令：万一日后 CCNR-Com 给 /a 加了新分支，不至于被我们抹掉
        for (CommandNode<CommandSourceStack> child : existing.getChildren()) {
            if (!ARG_MESSAGE.equals(child.getName())) {
                ours.addChild(child);
            }
        }
        root.addChild(ours);
        installed = ours;

        // /admin 是对 /a 的 redirect，redirect 目标在 build 时就固定了；
        // 不重新指向我们的节点的话，/admin <消息> 仍然走旧路径、仍然回绝普通玩家
        CommandNode<CommandSourceStack> alias = root.getChild(ALIAS_LITERAL);
        boolean aliasRepointed = false;
        if (alias instanceof LiteralCommandNode<CommandSourceStack> aliasLiteral
                && aliasLiteral.getRedirect() == existing) {
            root.addChild(Commands.literal(ALIAS_LITERAL).redirect(ours).build());
            aliasRepointed = true;
        }

        // 日志要如实反映究竟接管了什么：别名没重指（例如对方改了注册方式）必须能看出来，
        // 否则 /admin 会静默绕过接管，而日志却宣称一切正常
        if (aliasRepointed) {
            LOGGER.info("[CCNR-PM] 已接管 /{} 与 /{}：管理员仍走 CCNR-Com 管理通讯，其余玩家改为打开举报工单面板", ROOT_LITERAL, ALIAS_LITERAL);
        } else {
            LOGGER.warn(
                    "[CCNR-PM] 已接管 /{}，但未找到指向它的 /{} 别名（对方可能改了注册方式）：经 /{} 执行的普通玩家不会被导向举报面板",
                    ROOT_LITERAL,
                    ALIAS_LITERAL,
                    ALIAS_LITERAL);
        }
    }

    /** 裸 {@code /a}：管理员看原用法提示；普通玩家直接开面板（内容留空）。 */
    private static int onBare(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        CommandSourceStack source = ctx.getSource();
        ServerPlayer player = source.getPlayer();
        if (player != null && !PmPermissions.canAdminChat(player)) {
            return openPanel(source, player, "");
        }
        if (originalBareCommand != null) {
            return originalBareCommand.run(ctx);
        }
        return usage(source);
    }

    /** 带消息的 {@code /a <消息>}：管理员发管理通讯，普通玩家把消息作为举报内容开面板。 */
    private static int onMessage(CommandContext<CommandSourceStack> ctx, String message) throws CommandSyntaxException {
        CommandSourceStack source = ctx.getSource();
        ServerPlayer player = source.getPlayer();

        // 控制台/命令方块：没有「玩家」可弹面板，保持原行为
        if (player == null) {
            if (originalMessageCommand != null) return originalMessageCommand.run(ctx);
            return usage(source);
        }

        if (PmPermissions.canAdminChat(player)) {
            if (originalMessageCommand != null) return originalMessageCommand.run(ctx);
            // CCNR-Com 缺席却存在 /a：说明是同名命令被别人占用，绝不冒名发送
            source.sendFailure(Component.translatable("ccnr_pm.report.admin_unavailable"));
            return 0;
        }

        return openPanel(source, player, message);
    }

    /** 非管理员路径：把消息预填进工单面板。 */
    private static int openPanel(CommandSourceStack source, ServerPlayer player, String prefill) {
        TicketService service = TicketService.get();
        if (service == null) {
            source.sendFailure(Component.translatable("ccnr_pm.report.err.not_ready"));
            return 0;
        }
        service.sendOpenPanel(player, prefill);
        source.sendSuccess(
                () -> Component.translatable(
                        prefill.isBlank() ? "ccnr_pm.report.opened_empty" : "ccnr_pm.report.opened", SOURCE_COMMAND),
                false);
        return 1;
    }

    private static int usage(CommandSourceStack source) {
        source.sendFailure(Component.translatable("ccnr_pm.report.admin_unavailable"));
        return 0;
    }

    private static Command<CommandSourceStack> commandOf(CommandNode<CommandSourceStack> node) {
        return node == null ? null : node.getCommand();
    }
}
