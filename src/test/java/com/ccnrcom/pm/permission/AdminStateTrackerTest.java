/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.pm.permission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 权限补发记账的门禁。
 *
 * <p>这套机制只有三个方向会出错，三个方向都在本测试里钉死：
 * <ul>
 *   <li>**判得太松**（每次都发）→ 每个玩家每秒一个包，白费流量；</li>
 *   <li>**判得太紧**（漏发）→ 用户报的那个缺陷复发：拿到权限后客户端依旧打不开管理界面；</li>
 *   <li>**先记账再发包**→ 发包失败（客户端没装模组 / 通道还没协商好）时被记成「已下发」，
 *       记录永久堵死重试，症状与上一条完全一样，却更难查。</li>
 * </ul>
 * 因此记账是「查 / 记」两步：{@link AdminStateTracker#needsSend} 不改状态，
 * {@link AdminStateTracker#markSent} 只能在下发成功之后调用。
 *
 * <p>另外还钉住「记账有界」——它按 UUID 索引，不加约束就会随历史上线过的玩家无界增长。
 */
class AdminStateTrackerTest {

    private static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID BOB = UUID.fromString("00000000-0000-0000-0000-0000000000b2");

    /** 模拟一次「查 → 发 → 成功才记」。 */
    private static boolean deliver(AdminStateTracker t, UUID uuid, boolean admin, boolean sendSucceeds) {
        if (!t.needsSend(uuid, admin)) return false;
        if (!sendSucceeds) return true; // 需要发，但没发成功 → 不记账，下次还要发
        t.markSent(uuid, admin);
        return true;
    }

    @Test
    @DisplayName("首次必须下发（服务端还没告诉过客户端任何答案）")
    void firstTimeSends() {
        AdminStateTracker t = new AdminStateTracker();
        assertTrue(t.needsSend(ALICE, false));
        assertTrue(t.needsSend(BOB, true));
    }

    @Test
    @DisplayName("值不变就不发（稳态零流量，而不是每人每秒一个包）")
    void unchangedIsSilent() {
        AdminStateTracker t = new AdminStateTracker();
        assertTrue(deliver(t, ALICE, true, true));
        for (int i = 0; i < 100; i++) {
            assertFalse(t.needsSend(ALICE, true), "值没变却又要求发包——稳态会变成流量");
        }
        // false → false 同样静默：非管理员不该被每秒刷一次
        AdminStateTracker t2 = new AdminStateTracker();
        assertTrue(deliver(t2, ALICE, false, true));
        assertFalse(t2.needsSend(ALICE, false));
    }

    @Test
    @DisplayName("值翻转必须下发——两个方向都要（授权与降权）")
    void flipSends() {
        AdminStateTracker t = new AdminStateTracker();
        assertTrue(deliver(t, ALICE, false, true));
        // 授权：这就是「上线后被 /op 却依旧打不开界面」那条路径的修复点
        assertTrue(deliver(t, ALICE, true, true));
        assertFalse(t.needsSend(ALICE, true));
        // 降权：反向收敛，否则管理界面不会被自动关掉
        assertTrue(deliver(t, ALICE, false, true));
        assertFalse(t.needsSend(ALICE, false));
    }

    @Test
    @DisplayName("查不改记账：只问不记，下次仍然说「要发」")
    void queryDoesNotMutate() {
        AdminStateTracker t = new AdminStateTracker();
        for (int i = 0; i < 5; i++) {
            assertTrue(t.needsSend(ALICE, true));
        }
        assertEquals(0, t.size(), "needsSend 不该写入任何记录（否则会提前把「已下发」记上）");
    }

    @Test
    @DisplayName("发不出去就不记账：通道晚一点可用时必须还能重试（否则又是打不开界面）")
    void failedDeliveryKeepsRetrying() {
        AdminStateTracker t = new AdminStateTracker();
        // 通道不在：这一轮「需要发但发不出去」
        assertTrue(deliver(t, ALICE, true, false));
        assertEquals(0, t.size(), "没发出去就不该留下记录");
        assertTrue(t.needsSend(ALICE, true), "发不出去的值必须继续被要求下发——这才是重试的依据");
        // 通道可用后补发成功，此后静默
        assertTrue(deliver(t, ALICE, true, true));
        assertFalse(t.needsSend(ALICE, true));
        assertEquals(1, t.size());
    }

    @Test
    @DisplayName("逐个玩家独立记账（一个人的权限变化不该影响另一个人）")
    void perPlayerIsolation() {
        AdminStateTracker t = new AdminStateTracker();
        deliver(t, ALICE, false, true);
        deliver(t, BOB, false, true);
        deliver(t, ALICE, true, true);
        assertFalse(t.needsSend(BOB, false), "Bob 的值没变，不该被 Alice 的变化带走");
        assertFalse(t.needsSend(ALICE, true));
    }

    @Test
    @DisplayName("forget 之后重新下发（重新登录必须拿到权威答案）")
    void forgetForcesResend() {
        AdminStateTracker t = new AdminStateTracker();
        deliver(t, ALICE, true, true);
        assertFalse(t.needsSend(ALICE, true));
        t.forget(ALICE);
        assertTrue(t.needsSend(ALICE, true), "登录路径与轮询路径共用记账，清掉后必须重发");
    }

    @Test
    @DisplayName("记账有界：forget 真的把条目删掉（缓存不得超过在线人数）")
    void forgetBoundsTheCache() {
        AdminStateTracker t = new AdminStateTracker();
        for (int i = 0; i < 50; i++) {
            deliver(t, new UUID(0L, i), false, true);
        }
        assertEquals(50, t.size());
        for (int i = 0; i < 50; i++) {
            t.forget(new UUID(0L, i));
        }
        assertEquals(0, t.size());
    }

    @Test
    @DisplayName("null UUID 不炸（防御：调用点取的是 player.getUUID()，理论上不为空）")
    void nullUuidIsSafe() {
        AdminStateTracker t = new AdminStateTracker();
        t.forget(null);
        t.markSent(null, true);
        assertEquals(0, t.size());
        assertTrue(t.needsSend(null, true));
    }

    @Test
    @DisplayName("clear 复位（服务端停止后的对称清理）")
    void clearResets() {
        AdminStateTracker t = new AdminStateTracker();
        deliver(t, ALICE, true, true);
        assertFalse(t.needsSend(ALICE, true));
        t.clear();
        assertEquals(0, t.size());
        assertTrue(t.needsSend(ALICE, true), "清空后必须重新下发");
    }
}
