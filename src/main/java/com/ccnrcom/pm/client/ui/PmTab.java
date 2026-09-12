/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.pm.client.ui;

import net.minecraft.client.gui.GuiGraphics;

/**
 * 管理面板的一个页签（TAG）。
 *
 * <p>为什么页签是普通类而不是 Screen：面板只有一个顶层 Screen，页签共享它的输入分发与
 * 视觉外壳（标题栏、页签条、关闭按钮）。若每个页签各做一个 Screen，切换时整屏重建、
 * 滚位与选中状态全丢，而且键盘/鼠标路由要各写一遍。
 *
 * <p>约定：页签只负责**自己那块矩形**内的绘制与命中；矩形由面板在 {@link #rebuild} 时给出。
 * 页签不得自行注册 widget——需要通过 {@code PmAdminScreen.addPanelWidget} 转交，
 * 因为只有 Screen 能持有 widget 生命周期。
 */
public interface PmTab {

    /** 页签 id（同时是语言键后缀 {@code ccnr_pm.gui.panel.tab.<id>}）。 */
    String id();

    /** 面板尺寸变化或数据更新时调用：重算内部矩形、重建 widget。 */
    void rebuild(int x1, int y1, int x2, int y2);

    void render(GuiGraphics g, int mouseX, int mouseY);

    /** @return 是否吞掉该次点击 */
    boolean mouseClicked(double mouseX, double mouseY, int button);

    boolean keyPressed(int keyCode, int scanCode, int modifiers);

    boolean mouseScrolled(double mouseX, double mouseY, double delta);

    boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY);

    boolean mouseReleased(double mouseX, double mouseY, int button);
}
