/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.pm.wiring;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 「推送点接线」门禁。
 *
 * <h2>为什么需要它（真实事故）</h2>
 * 看板做出来之后，用户实机发现「进入服务器不显示已有工单，只有新工单才出现」。
 * 根因不是设计问题，而是：{@code TicketService.sendBoard()} 写好了、**却没有任何地方调用它**——
 * 登录时的推送那一步在编辑过程中丢失了。
 *
 * <p>这类「方法写好了但触发点没接上」与「加了包忘了注册」是同一类缺陷：
 * <b>编译通过、单测全绿、只有实机才暴露</b>。项目禁止起服务器验证（见 AGENTS），
 * 因此必须把它变成静态可查的断言。
 *
 * <h2>这个测试检查什么、不检查什么</h2>
 * 它只检查**接线是否存在**（某个触发点是否引用了推送方法），
 * 不检查推送内容是否正确、时机是否恰当——那些仍由实机验证。
 * 换句话说：它能挡住「一个都没接」，挡不住「接错了地方」。
 */
class EventWiringTest {

    private static Path projectRoot() {
        Path dir = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        for (int i = 0; i < 6 && dir != null; i++) {
            if (Files.isRegularFile(dir.resolve("gradle.properties")) && Files.isDirectory(dir.resolve("src"))) {
                return dir;
            }
            dir = dir.getParent();
        }
        return fail("找不到项目根目录：user.dir=" + System.getProperty("user.dir"));
    }

    private static String read(String relative) {
        try {
            return Files.readString(projectRoot().resolve(relative), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return fail("读取失败: " + relative + " —— " + e);
        }
    }

    private static int countMatches(String source, String regex) {
        Matcher m = Pattern.compile(regex).matcher(source);
        int n = 0;
        while (m.find()) n++;
        return n;
    }

    private static final String MOD = "src/main/java/com/ccnrcom/pm/CCNRPMMod.java";

    private static final String SERVICE = "src/main/java/com/ccnrcom/pm/ticket/TicketService.java";

    // ------------------------------------------------------------------

    @Test
    @DisplayName("登录时必须推送看板（否则「进去看不到已有工单，只有新工单才出现」）")
    void boardIsPushedOnLogin() {
        String mod = read(MOD);
        assertTrue(
                mod.contains("sendBoard("),
                "CCNRPMMod 没有调用 TicketService.sendBoard —— 管理员登录时不会拿到当前已有的活跃工单，" + "只能等新工单产生（这正是曾经发生过的缺陷）");
        assertTrue(mod.contains("PlayerLoggedInEvent"), "登录推送应当挂在 PlayerLoggedInEvent 上；若换了事件，请同步本测试与 docs/03 §8");
    }

    @Test
    @DisplayName("登录推送必须只对管理员发（普通玩家没有看板，白发包也白抓头像）")
    void loginPushIsAdminGated() {
        String mod = read(MOD);
        assertTrue(mod.contains("canAdminTicket"), "登录推送必须先用 canAdminTicket 判定——否则会给每个普通玩家发看板数据");
    }

    @Test
    @DisplayName("建单与每个管理动作都必须广播看板（否则卡片状态不更新、办结后也不消失）")
    void boardIsBroadcastOnCreateAndAction() {
        String service = read(SERVICE);
        int calls = countMatches(service, "broadcastBoard\\(");
        // 至少三处：submit（新工单进入活跃集合）、claim、close/reject（离开活跃集合或状态变化）
        assertTrue(
                calls >= 3,
                "TicketService 里 broadcastBoard( 的调用点只有 " + calls + " 处，"
                        + "至少应有 3 处（建单 / 认领 / 办结或驳回）；"
                        + "漏掉任何一处都会让看板与真实状态不一致");
    }

    @Test
    @DisplayName("看板数据只在服务端构建（客户端不得自行拼装工单数据）")
    void boardIsBuiltServerSide() {
        String service = read(SERVICE);
        assertTrue(service.contains("public TicketBoard board("), "看板应由 TicketService.board() 构建");

        // 客户端只允许通过 Packet 收到的 JSON 还原（TicketBoard.fromJson），不得自己 new Card
        String overlay = read("src/main/java/com/ccnrcom/pm/client/hud/TicketNoticeOverlay.java");
        assertTrue(overlay.contains("TicketBoard.fromJson"), "客户端应当从服务端下发的 JSON 还原看板");
        assertTrue(!overlay.contains("cardOf("), "客户端不得自行构造看板卡片——那等于在客户端造了第二个权威源");
    }

    @Test
    @DisplayName("推送入口必须是 public（否则触发点根本调用不到）")
    void pushEntryPointsArePublic() {
        String service = read(SERVICE);
        assertTrue(service.contains("public void sendBoard("), "sendBoard 必须是 public");
        assertTrue(service.contains("public void broadcastBoard("), "broadcastBoard 必须是 public");
    }

    @Test
    @DisplayName("客户端收到看板后必须真的交给浮层（不是收下就丢）")
    void clientRoutesBoardToOverlay() {
        String handler = read("src/main/java/com/ccnrcom/pm/client/PmClientPacketHandler.java");
        assertTrue(handler.contains("onTicketBoard"), "客户端包处理器应当有 onTicketBoard");

        String packets = read("src/main/java/com/ccnrcom/pm/network/PmPackets.java");
        assertTrue(
                packets.contains("PmClientPacketHandler.onTicketBoard"),
                "TicketBoardS2C 的 handle 必须把载荷交给客户端处理器，否则数据到不了浮层");
    }

    // ------------------------------------------------------------------
    // 管理界面的权限门（用户要求：没有权限不能打开 P 界面 / K 界面）
    // ------------------------------------------------------------------

    private static final String PM_CHANNEL = "src/main/java/com/ccnrcom/pm/network/PmChannel.java";

    private static final String PM_CLIENT_EVENTS = "src/main/java/com/ccnrcom/pm/client/PmClientEvents.java";

    private static final String PM_ADMIN_SCREEN = "src/main/java/com/ccnrcom/pm/client/PmAdminScreen.java";

    private static final String PM_BOARD_SCREEN = "src/main/java/com/ccnrcom/pm/client/PmBoardScreen.java";

    @Test
    @DisplayName("AdminStateS2C 必须注册（加了包忘注册：编译通过、实机才炸）")
    void adminStatePacketIsRegistered() {
        String channel = read(PM_CHANNEL);
        assertTrue(
                channel.contains("PmPackets.AdminStateS2C"),
                "AdminStateS2C 没有在 PmChannel 注册 —— 客户端永远收不到权限状态，" + "管理界面那道门会一直处于「默认拒绝」，管理员也打不开面板");
    }

    @Test
    @DisplayName("服务端登录时必须下发权限状态（否则客户端无权可判）")
    void adminStateIsPushedOnLogin() {
        String mod = read(MOD);
        assertTrue(
                mod.contains("AdminStateSync.push("),
                "CCNRPMMod 登录时没有下发权限状态 —— 客户端手里没有权限信息，" + "按 P 会恢复正常（无权限者也能打开管理界面）");
    }

    /**
     * 「会话中途拿到权限后依旧打不开管理界面」那个缺陷的接线门禁。
     *
     * <p>根因不是界面画不出来，而是客户端手里的权限是**过期且自认为权威的**：
     * 只在登录时下发一次 boolean，之后 {@code /op} 或权限插件改节点都无法让它收敛，
     * 而客户端还会据此把服务端刚打开的面板立刻关掉。修法是服务端周期复查 +
     * 只在值变化时补发（见 {@code permission.AdminStateSync}）。
     *
     * <p>本测试只断言**接线存在**（复查被挂上、补发走记账、退出与停服有对称清理），
     * 收敛时长与真实权限插件的配合仍由实机验证。
     */
    @Test
    @DisplayName("会话中途的权限变化必须能收敛（复查已挂上 + 只在变化时发 + 对称清理）")
    void adminStateConvergesMidSession() {
        String mod = read(MOD);
        assertTrue(
                mod.contains("AdminStateSync.onServerTick("),
                "服务端 tick 里没有复查权限 —— 会话中途被 /op 或权限插件授权的玩家，" + "客户端标志永远停在「已确认的 false」，按 P 打不开管理界面（用户报过这个现象）");
        assertTrue(mod.contains("AdminStateSync.forget("), "玩家退出必须丢掉权限记账，否则缓存随历史上线过的玩家无界增长");
        assertTrue(mod.contains("AdminStateSync.shutdown()"), "服务端停止必须清空权限记账（对称清理）");

        String sync = read("src/main/java/com/ccnrcom/pm/permission/AdminStateSync.java");
        assertTrue(sync.contains("AdminStateS2C"), "复查命中后必须真的下发 AdminStateS2C");
        assertTrue(
                sync.contains("needsSend(") && sync.contains("markSent("),
                "补发必须走「先问、再发、发成功才记账」：每次都发会变成每人每秒一个包，" + "而先记账再发会把「没发出去」记成「已下发」，那条记录永久堵死重试");
        assertTrue(sync.contains("if (deliver(player, admin))"), "轮询路径必须只在发送成功后才记账——否则通道晚一点可用时就再也补不上了");
    }

    @Test
    @DisplayName("服务端驱动打开的界面 = 权威证明：客户端必须顺手置 admin=true（否则面板会被自己关掉）")
    void serverOpenedPanelTrustsTheServer() {
        String packets = read("src/main/java/com/ccnrcom/pm/network/PmPackets.java");
        assertTrue(
                packets.contains("PmClientPacketHandler.onOpenAdminPanel"),
                "OpenAdminPanelS2C 的 handle 必须走客户端处理器（它负责顺手刷新权限镜像）");

        String handler = read("src/main/java/com/ccnrcom/pm/client/PmClientPacketHandler.java");
        int at = handler.indexOf("onOpenAdminPanel()");
        assertTrue(at >= 0, "PmClientPacketHandler 缺少 onOpenAdminPanel");
        assertTrue(
                handler.substring(at).contains("PmClientState.setAdmin(true)"),
                "onOpenAdminPanel 里必须把权限镜像置为 true —— 服务端在发这个包之前已经判过 "
                        + "canAdminTicket，不置的话「上线后才被 /op」的玩家会看到面板一闪就被 tick() 关掉");
    }

    @Test
    @DisplayName("P 键两条路径都必须先判权限（看板与管理面板都是管理界面）")
    void keyPressIsPermissionGated() {
        String events = read(PM_CLIENT_EVENTS);
        int gates = countMatches(events, "PmClientState\\.isAdmin\\(\\)");
        assertTrue(
                gates >= 2,
                "PmClientEvents 里 PmClientState.isAdmin() 只出现 " + gates + " 处；"
                        + "短按（交互看板）与长按（管理面板）**各自**都要判一次，"
                        + "漏掉短按等于普通玩家仍能打开管理看板");
        assertTrue(
                !events.contains("mc.setScreen(new PmBoardScreen())")
                        || events.indexOf("isAdmin()") < events.indexOf("new PmBoardScreen()"),
                "权限判定必须发生在打开界面之前");
    }

    @Test
    @DisplayName("两个管理界面都要在权限被收回时自动关闭")
    void adminScreensAutoCloseWhenPermissionRevoked() {
        for (String path : new String[] {PM_ADMIN_SCREEN, PM_BOARD_SCREEN}) {
            String src = read(path);
            assertTrue(
                    src.contains("PmClientState.knownNonAdmin()"),
                    path + " 没有复核 PmClientState.knownNonAdmin() —— 权限被收回后界面会一直留着");
            assertTrue(src.contains("public void tick()"), path + " 应当用 tick() 做逐帧复核");
        }
    }

    @Test
    @DisplayName("权限不足时服务端补发 admin=false（让被降权的客户端自我纠正）")
    void serverRefreshesPermissionOnDeny() {
        String handlers = read("src/main/java/com/ccnrcom/pm/network/PmServerHandlers.java");
        int denials = countMatches(handlers, "denyAdmin\\(player\\)");
        assertTrue(
                denials >= 3,
                "PmServerHandlers 里 denyAdmin(player) 只有 " + denials + " 处；"
                        + "每个被拒绝的管理请求都应顺手补发 admin=false，否则降权后客户端标志不会收敛");
    }
}
