/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.pm.permission;

import java.util.Optional;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.server.permission.PermissionAPI;
import net.minecraftforge.server.permission.events.PermissionGatherEvent;
import net.minecraftforge.server.permission.nodes.PermissionNode;
import net.minecraftforge.server.permission.nodes.PermissionTypes;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * 权限判据。
 *
 * <p>本模组**独立**于 CCNR-RP / CCNR-Com，因此不引用它们的类。但「管理员发送消息」这条命令的所有权在
 * CCNR-Com，它的资格判据是「OP ≥ 2 **或** 拥有权限节点 {@code ccnrcom.admin.chat}」。
 * 如果我们只按 OP 判，那些用 LuckPerms 单独授了该节点、却没有 OP 的管理员会被错误地当成普通玩家，
 * 一执行 {@code /a} 就被导向举报面板——这是本功能最不能出的错。
 *
 * <p>解法：运行时从 {@link PermissionAPI#getRegisteredNodes()} **按名字**解析对方注册的节点，
 * 再以同一套 {@code PermissionAPI} 求值。这样既不产生编译期依赖，判据又与 CCNR-Com 完全一致；
 * 对方改了判定我们也不会失配（我们只做「同样的或运算」）。
 */
public final class PmPermissions {

    private static final Logger LOGGER = LogManager.getLogger("ccnr_pm");

    /** 本模组：工单管理（列表/查看/认领/办结/驳回）。 */
    public static final PermissionNode<Boolean> ADMIN_TICKET =
            new PermissionNode<>("ccnrpm", "admin.ticket", PermissionTypes.BOOLEAN, (player, uuid, context) -> false);

    /** CCNR-Com 注册的管理通讯节点名（用于动态解析，不编译期引用）。 */
    public static final String FOREIGN_ADMIN_CHAT = "ccnrcom.admin.chat";

    private static volatile PermissionNode<Boolean> resolvedAdminChat;
    private static volatile boolean resolveAttempted;

    private PmPermissions() {}

    public static void onGatherNodes(PermissionGatherEvent.Nodes event) {
        event.addNodes(ADMIN_TICKET);
    }

    /** 工单管理资格：OP ≥ 2 或拥有 {@code ccnrpm.admin.ticket}。 */
    public static boolean canAdminTicket(ServerPlayer player) {
        if (player == null) return false;
        if (player.hasPermissions(2)) return true;
        return query(player, ADMIN_TICKET);
    }

    /**
     * 管理通讯资格：与 CCNR-Com 的 {@code Permissions.canAdmin} 判定保持一致。
     *
     * <p>对应用户：**这条判据决定 {@code /a} 是「发管理消息」还是「开工单面板」**，
     * 所以宁可保守——判不出对方节点时按「不是管理员」处理会误伤使用权限插件的人，
     * 因此解析失败会明确记一条 WARN，便于管理员定位。
     */
    public static boolean canAdminChat(ServerPlayer player) {
        if (player == null) return false;
        if (player.hasPermissions(2)) return true;
        PermissionNode<Boolean> node = adminChatNode();
        if (node == null) return false;
        return query(player, node);
    }

    /** 动态解析 {@code ccnrcom.admin.chat}；解析结果缓存（节点在服务端启动前注册完毕）。 */
    private static PermissionNode<Boolean> adminChatNode() {
        PermissionNode<Boolean> cached = resolvedAdminChat;
        if (cached != null) return cached;
        if (resolveAttempted && cached == null) {
            // 已尝试过且没找到：CCNR-Com 可能压根没装，不必每次调用都遍历
            return null;
        }
        synchronized (PmPermissions.class) {
            if (resolvedAdminChat != null) return resolvedAdminChat;
            try {
                for (PermissionNode<?> node : PermissionAPI.getRegisteredNodes()) {
                    if (node == null || node.getNodeName() == null) continue;
                    if (!FOREIGN_ADMIN_CHAT.equals(node.getNodeName())) continue;
                    if (node.getType() != PermissionTypes.BOOLEAN) continue;
                    @SuppressWarnings("unchecked")
                    PermissionNode<Boolean> cast = (PermissionNode<Boolean>) node;
                    resolvedAdminChat = cast;
                    return cast;
                }
                LOGGER.warn("[CCNR-PM] 未找到权限节点 {}；CCNR-Com 未安装时属正常，若已安装请反馈", FOREIGN_ADMIN_CHAT);
            } catch (Exception e) {
                LOGGER.error("[CCNR-PM] 解析权限节点失败: {}", e.toString());
            } finally {
                resolveAttempted = true;
            }
        }
        return resolvedAdminChat;
    }

    /** 权限求值；无生效的 PermissionHandler 时 API 可能抛异常，此处兜底为「无权限」。 */
    private static boolean query(ServerPlayer player, PermissionNode<Boolean> node) {
        try {
            Boolean v = PermissionAPI.getPermission(player, node);
            return v != null && v;
        } catch (Exception e) {
            LOGGER.error("[CCNR-PM] 权限查询失败（{}）: {}", node.getNodeName(), e.toString());
            return false;
        }
    }

    /** 诊断用：当前管理员节点的解析结果（{@code /pm status} 展示）。 */
    public static Optional<String> adminChatNodeState() {
        PermissionNode<Boolean> n = adminChatNode();
        return Optional.of(n == null ? "未解析到" : "已解析 " + n.getNodeName());
    }
}
