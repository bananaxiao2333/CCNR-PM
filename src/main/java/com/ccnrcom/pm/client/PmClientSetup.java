/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.pm.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.settings.KeyConflictContext;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;

/**
 * 客户端按键与刷新循环。
 *
 * <p>面板的打开方式有两条：按键（本类）与 {@code /pm panel} 命令。
 * 命令那条不依赖客户端按键绑定，服务端脚本/后台也能给管理员开面板，
 * 因此两条都保留而不是只做按键。
 *
 * <p>按键用 {@code KeyConflictContext.IN_GAME}：只在游戏内生效，
 * 不会在聊天框打字时误触（和 K 面板同一套做法）。
 */
@Mod.EventBusSubscriber(
        modid = com.ccnrcom.pm.CCNRPMMod.MODID,
        value = Dist.CLIENT,
        bus = Mod.EventBusSubscriber.Bus.MOD)
public final class PmClientSetup {

    /** 打开管理面板，默认 P（可在「控制」里改；与 CCNR-RP 的 K 面板互不冲突）。 */
    public static final KeyMapping OPEN_PANEL = new KeyMapping(
            "key.ccnr_pm.admin_panel",
            KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_P,
            "key.categories.ccnr_pm");

    private PmClientSetup() {}

    @SubscribeEvent
    public static void registerKeys(RegisterKeyMappingsEvent event) {
        event.register(OPEN_PANEL);
    }
}
