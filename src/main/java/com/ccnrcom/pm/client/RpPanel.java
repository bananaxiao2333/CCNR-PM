/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.pm.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

/**
 * 打开 CCNR-RP 的管理面板（**可选**，用反射，不产生编译期依赖）。
 *
 * <h2>为什么要反射</h2>
 * 本模组与 CCNR-RP 是**互相独立**的仓库，AGENTS 也明确禁止跨仓库引用。
 * 但用户要求「加个打开 CCNR-RP 管理面板的按钮（如果可用）」——「如果可用」正是软依赖的语义：
 * 装了就用，没装就不显示按钮。
 *
 * <p>因此这里按类名反射加载并实例化，**只在客户端**（那个界面是客户端类）。
 * 任何一步失败（类不在、构造器变了、不是 Screen）都静默降级为「不可用」，
 * 由调用方决定是否画按钮——绝不让一个可选功能的缺失影响本模组自身的功能。
 *
 * <h2>可用性结论会被缓存</h2>
 * 可用性在一次游戏会话内不会变（模组装没装是启动期确定的），而按钮每帧都要判断，
 * 因此把结果缓存起来，避免每帧都走一次 Class.forName。
 */
final class RpPanel {

    /** CCNR-RP 管理面板的类名（该仓库的界面包路径）。 */
    private static final String RP_ADMIN_SCREEN = "com.ccnrcom.rp.client.RpAdminScreen";

    private static volatile Boolean cached;

    private RpPanel() {}

    /** CCNR-RP 管理面板是否可用（结果缓存）。 */
    static boolean isAvailable() {
        Boolean c = cached;
        if (c != null) return c;
        boolean available = resolve() != null;
        cached = available;
        return available;
    }

    /** 尝试打开；返回是否成功。 */
    static boolean open() {
        Class<?> cls = resolve();
        if (cls == null) return false;
        try {
            Object screen = cls.getDeclaredConstructor().newInstance();
            if (!(screen instanceof Screen s)) return false;
            Minecraft.getInstance().setScreen(s);
            return true;
        } catch (Throwable t) {
            // 对方改了构造器签名等情况：降级为不可用，不影响本模组功能
            cached = false;
            return false;
        }
    }

    /** 解析类并确认它确实是 Screen 的子类。 */
    private static Class<?> resolve() {
        try {
            Class<?> cls = Class.forName(RP_ADMIN_SCREEN);
            if (!Screen.class.isAssignableFrom(cls)) return null;
            cls.getDeclaredConstructor(); // 必须有无参构造器，否则调用时才发现就太晚了
            return cls;
        } catch (Throwable t) {
            return null;
        }
    }
}
