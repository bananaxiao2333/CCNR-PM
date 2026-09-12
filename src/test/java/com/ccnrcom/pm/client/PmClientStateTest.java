/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.pm.client;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 客户端管理权限镜像的不变量。
 *
 * <p>这里钉的是**默认拒绝**与**"未知 ≠ 已知不是管理员"**两条语义 —— 它们都直接决定
 * "没权限的人能不能打开管理界面"，写错一次就是权限门失效：
 * <ul>
 *   <li>默认值必须是拒绝：进程刚起来、以及任何一次 {@link PmClientState#reset()} 之后都不得为 {@code true}
 *       （否则跨服务器会沿用上一个服的管理权限）；</li>
 *   <li>"未知"与"已知不是"必须可区分：服务端主动开面板（{@code /pm panel}）可能早于权限包到达，
 *       若把"未知"也当成"确认非管理员"，那一帧就会把服务端刚打开的面板关掉。</li>
 * </ul>
 */
class PmClientStateTest {

    @AfterEach
    void resetAfterEach() {
        PmClientState.reset(); // 静态状态：用例之间必须互不影响
    }

    @Test
    @DisplayName("默认拒绝：没收到服务端答案时一律不是管理员")
    void defaultDeny() {
        PmClientState.reset();
        assertFalse(PmClientState.isAdmin(), "未收到服务端答案时必须是 false（fail-closed）");
        assertFalse(PmClientState.known(), "未收到答案时 known 必须为 false");
        assertFalse(PmClientState.knownNonAdmin(), "未收到答案 ≠ 已确认不是管理员");
    }

    @Test
    @DisplayName("收到 false 后：既是已知、也是已知非管理员")
    void knownNonAdminAfterExplicitFalse() {
        PmClientState.setAdmin(false);
        assertFalse(PmClientState.isAdmin());
        assertTrue(PmClientState.known(), "收到过答案即为已知");
        assertTrue(PmClientState.knownNonAdmin(), "此时才允许自动关闭已打开的管理界面");
    }

    @Test
    @DisplayName("收到 true 后：是管理员，且不属于「已知非管理员」")
    void adminAfterExplicitTrue() {
        PmClientState.setAdmin(true);
        assertTrue(PmClientState.isAdmin());
        assertTrue(PmClientState.known());
        assertFalse(PmClientState.knownNonAdmin(), "管理员不能被自动关闭逻辑误判");
    }

    @Test
    @DisplayName("复位回到「未知」而不是「已知非管理员」")
    void resetGoesBackToUnknown() {
        PmClientState.setAdmin(true);
        PmClientState.reset();
        assertFalse(PmClientState.isAdmin(), "换服后必须重新等服务端下发，不得沿用上一个服的权限");
        assertFalse(PmClientState.known(), "回到未知：新服务器会在登录时重新下发答案");
        assertFalse(PmClientState.knownNonAdmin(), "复位不得被当成「已确认非管理员」——否则刚进服那一帧会把服务端主动打开的面板关掉");
    }

    @Test
    @DisplayName("权限往复切换都立即生效（降权 → 提权 → 降权）")
    void togglingIsImmediate() {
        PmClientState.setAdmin(true);
        assertFalse(PmClientState.knownNonAdmin());
        PmClientState.setAdmin(false);
        assertTrue(PmClientState.knownNonAdmin(), "降权后必须立刻可被自动关闭逻辑看到");
        PmClientState.setAdmin(true);
        assertFalse(PmClientState.knownNonAdmin(), "重新提权后不得再触发自动关闭");
    }
}
