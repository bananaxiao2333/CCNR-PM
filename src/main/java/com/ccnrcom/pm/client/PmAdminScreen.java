/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.pm.client;

import com.ccnrcom.pm.client.tab.PmEventsTab;
import com.ccnrcom.pm.client.tab.PmMatchTab;
import com.ccnrcom.pm.client.tab.PmTicketsTab;
import com.ccnrcom.pm.client.ui.PmButton;
import com.ccnrcom.pm.client.ui.PmScrollbar;
import com.ccnrcom.pm.client.ui.PmTab;
import com.ccnrcom.pm.client.ui.PmTheme;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * CCNR-PM 管理面板（单屏终端风格，页签式）。
 *
 * <p>打开方式：按键（见 {@code PmClientSetup} 的按键绑定）或 {@code /pm panel} 命令。
 * 面板打开时会向服务端要一份权威数据。
 *
 * <h2>为什么是 Screen 而不是 HUD 浮层</h2>
 * 管理动作（认领 / 办结 / 驳回）需要**点击**。HUD 浮层在无界面时鼠标被相机抓取、
 * 根本没有光标坐标，无法点击；而 Screen 天然有光标与事件分发。
 * 因此通知卡片负责「抬头看一眼」，本面板负责「动手处理」。
 *
 * <h2>页签结构</h2>
 * 面板只提供一个外壳（标题栏 + 页签条 + 关闭按钮 + 输入分发），
 * 内容由 {@link PmTab} 实现承担。新增页签只需往 {@link #TABS} 列表里加一项。
 */
public final class PmAdminScreen extends Screen {

    private static final org.apache.logging.log4j.Logger LOGGER =
            org.apache.logging.log4j.LogManager.getLogger("ccnr_pm");

    /** 页签注册表：以后加「玩家检索」「处置记录」等页签在此追加即可。 */
    private static final List<String> TABS = List.of("tickets", "match", "events");

    /** 对局快照的轮询周期（客户端 tick）。1 秒：够让倒计时与事件流看起来是活的，又不至于变成一条持续流量。 */
    private static final int MATCH_POLL_TICKS = 20;

    private static final int MIN_W = 480;
    private static final int MIN_H = 260;
    private static final int TAB_W = 84;
    private static final int TAB_H = 18;

    /**
     * 需要**活数据**的页签 id。
     *
     * <p>轮询只在这些页签处于前台时进行：「对局 / 事件」的数据每秒都在变，而工单列表不是
     * （它是低频管理操作，动作后由服务端回推）。给工单页签也挂上轮询只是白费流量，
     * 还会让 `ClientTicketBoard.accept` 每秒触发一次界面重建。
     */
    private static final List<String> LIVE_TABS = List.of("match", "events");

    private static volatile PmAdminScreen open;

    private final Screen parent;
    private final List<PmTab> tabs = new ArrayList<>();
    private int activeTab;

    private int px1;
    private int py1;
    private int px2;
    private int py2;
    private int contentY1;

    /** 页签条命中区（与 {@link #TABS} 一一对应）。 */
    private final List<int[]> tabRects = new ArrayList<>();

    /** 轮询计数（只在 {@link #tick()} 里推进；到 {@link #MATCH_POLL_TICKS} 就再要一份对局快照）。 */
    private int matchPollCounter;

    public PmAdminScreen() {
        super(Component.translatable("ccnr_pm.gui.panel.title"));
        this.parent = Minecraft.getInstance().screen;
        for (String id : TABS) {
            switch (id) {
                case "tickets" -> tabs.add(new PmTicketsTab(this));
                case "match" -> tabs.add(new PmMatchTab());
                case "events" -> tabs.add(new PmEventsTab());
                    // fail-soft 而不是抛异常：页签条是按 tabs 的**实际内容**画的（不是按 TABS），
                    // 因此少一个页签不会留下一个点了没反应的按钮，界面仍然自洽。
                    // 「TABS 与实现不同步」这件事由 PmAdminTabsTest 在编译期拦下，这里只是最后一道兜底。
                default -> LOGGER.error("[CCNR-PM] TABS 里的页签 {} 没有对应的实现类，已跳过", id);
            }
        }
    }

    /** 已打开的面板（无则 null）。 */
    public static PmAdminScreen open() {
        return open;
    }

    /** 数据刷新时让已打开的面板重画（未打开则什么都不做）。 */
    public static void refreshIfOpen() {
        PmAdminScreen s = open;
        if (s != null) s.refreshWidgets();
    }

    /** 从按键/命令打开面板（会先关掉当前界面，与 K 面板一致）。 */
    public static void openPanel() {
        Minecraft.getInstance().setScreen(new PmAdminScreen());
    }

    /**
     * 供页签注册 widget 的桥。
     *
     * <p>页签不是 Screen，拿不到 {@code addRenderableWidget}；而 widget 生命周期只能由 Screen 持有。
     * 因此统一从这里转交，页签不得自行保存 widget 引用去注册。
     */
    public <T extends AbstractWidget> T addPanelWidget(T widget) {
        return addRenderableWidget(widget);
    }

    /** 重建页签内部布局与 widget（尺寸变化、选中变化、数据刷新时调用）。 */
    public void refreshWidgets() {
        clearWidgets();
        for (PmTab tab : tabs) {
            tab.rebuild(px1 + 10, contentY1, px2 - 10, py2 - 10);
        }
        // rebuild 里注册的 widget 会在 clearWidgets 之后重新挂上；
        // 但页签是在 rebuild 中调 addPanelWidget 的，因此顺序必须是 clear → rebuild
    }

    @Override
    protected void init() {
        open = this;

        int pw = Math.max(MIN_W, Math.min(width - 40, 720));
        int ph = Math.max(MIN_H, Math.min(height - 60, 420));
        pw = Math.min(pw, Math.max(200, width - 8));
        ph = Math.min(ph, Math.max(140, height - 8));
        px1 = Math.max(0, (width - pw) / 2);
        py1 = Math.max(0, (height - ph) / 2);
        px2 = px1 + pw;
        py2 = py1 + ph;

        // 页签条
        tabRects.clear();
        int tx = px1 + 10;
        for (int i = 0; i < tabs.size(); i++) {
            int w = Math.min(TAB_W, Math.max(52, (px2 - px1 - 20) / Math.max(1, tabs.size())));
            tabRects.add(new int[] {tx, py1 + 30, tx + w, py1 + 30 + TAB_H});
            tx += w + 4;
        }
        contentY1 = py1 + 30 + TAB_H + 6;

        // 先清空再让页签重建（页签会在 rebuild 里重新注册 widget）
        clearWidgets();
        for (PmTab tab : tabs) {
            tab.rebuild(px1 + 10, contentY1, px2 - 10, py2 - 10);
        }

        // 打开即向服务端要一份权威数据
        ClientTicketBoard.clearError();
        ClientTicketBoard.request("all", 1);
        // 对局快照也一并取一次：不取的话，切到「对局」页签会先看到一秒的「正在读取……」。
        // 错误行同样要清——那一行是固定占位的，留着上一次打开的失败原因会被误读成刚发生的
        ClientMatchState.clearError();
        ClientMatchState.request();
        matchPollCounter = 0;
    }

    // ------------------------------------------------------------------
    // 绘制
    // ------------------------------------------------------------------

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderBackground(g);

        PmTheme.terminalFrame(g, px1, py1, px2, py2, PmTheme.RADIUS_LARGE);
        PmTheme.gridOverlay(g, px1 + 1, py1 + 1, px2 - 1, py2 - 1);

        var font = Minecraft.getInstance().font;
        g.drawString(font, PmTheme.tag("CCNR"), px1 + 10, py1 + 8, PmTheme.TEXT_PRIMARY, false);
        g.drawString(
                font,
                tr("ccnr_pm.gui.panel.title"),
                px1 + 10 + font.width(PmTheme.tag("CCNR")) + 8,
                py1 + 8,
                PmTheme.TEXT_BRIGHT,
                false);
        // 关闭按钮（右上）
        int[] close = closeRect();
        String closeLabel = "X";
        boolean closeHover = PmButton.hit(mouseX, mouseY, close[0], close[1], close[2], close[3]);
        PmButton.draw(
                g,
                font,
                close[0],
                close[1],
                close[2],
                close[3],
                closeLabel,
                PmButton.Variant.SECONDARY,
                closeHover,
                true);

        renderTabs(g, mouseX, mouseY);

        active().ifPresent(tab -> tab.render(g, mouseX, mouseY));

        // widget（页签注册的输入框等）画在页签内容之上
        super.render(g, mouseX, mouseY, partialTick);

        PmTheme.scanlines(g, px1 + 1, py1 + 1, px2 - 1, py2 - 1);
    }

    private void renderTabs(GuiGraphics g, int mouseX, int mouseY) {
        var font = Minecraft.getInstance().font;
        for (int i = 0; i < tabs.size(); i++) {
            int[] r = tabRects.get(i);
            boolean active = i == activeTab;
            boolean hovered = PmButton.hit(mouseX, mouseY, r[0], r[1], r[2], r[3]);
            PmButton.draw(
                    g,
                    font,
                    r[0],
                    r[1],
                    r[2],
                    r[3],
                    tr("ccnr_pm.gui.panel.tab." + tabs.get(i).id()),
                    active ? PmButton.Variant.PRIMARY : PmButton.Variant.SECONDARY,
                    hovered,
                    true);
        }
    }

    private int[] closeRect() {
        return new int[] {px2 - 26, py1 + 6, px2 - 8, py1 + 24};
    }

    private java.util.Optional<PmTab> active() {
        if (activeTab < 0 || activeTab >= tabs.size()) return java.util.Optional.empty();
        return java.util.Optional.of(tabs.get(activeTab));
    }

    // ------------------------------------------------------------------
    // 输入：先给页签，再给外壳
    // ------------------------------------------------------------------

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        int[] close = closeRect();
        if (PmButton.hit(mx, my, close[0], close[1], close[2], close[3])) {
            onClose();
            return true;
        }
        for (int i = 0; i < tabRects.size(); i++) {
            int[] r = tabRects.get(i);
            if (PmButton.hit(mx, my, r[0], r[1], r[2], r[3])) {
                if (i != activeTab) {
                    activeTab = i;
                    refreshWidgets();
                }
                return true;
            }
        }
        if (active().map(t -> t.mouseClicked(mx, my, button)).orElse(false)) return true;
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        if (active().map(t -> t.mouseScrolled(mx, my, delta)).orElse(false)) return true;
        return super.mouseScrolled(mx, my, delta);
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        if (active().map(t -> t.mouseDragged(mx, my, button, dx, dy)).orElse(false)) return true;
        return super.mouseDragged(mx, my, button, dx, dy);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        active().ifPresent(t -> t.mouseReleased(mx, my, button));
        return super.mouseReleased(mx, my, button);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // 页签优先处理方向键等；Esc 留给面板自己关闭
        if (keyCode != 256
                && active().map(t -> t.keyPressed(keyCode, scanCode, modifiers)).orElse(false)) {
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(parent);
    }

    /**
     * 界面被替换/世界卸载时的清理。
     *
     * <p>**必须单独实现**：{@code Minecraft.setScreen(其它界面)} 只调 {@code removed()}、
     * **不调** {@code onClose()}。不清静态句柄的话，`refreshIfOpen()` 会去刷新一个已经不在屏幕上的实例
     * （`/pm panel` 的 S2C、看板回包都可能撞上）。
     */
    @Override
    public void removed() {
        if (open == this) open = null;
        PmScrollbar.endDrag();
        super.removed();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /**
     * 权限可能在面板打开期间被收回（服务端补发 {@code AdminStateS2C(false)}），因此每帧复核一次。
     *
     * <p>只在**已确认不是管理员**（{@link PmClientState#knownNonAdmin()}）时关闭，而不是"标志不为真就关"：
     * 服务端主动开面板（{@code /pm panel → OpenAdminPanelS2C}）可能早于权限包到达，用后一种判据会把刚被
     * 服务端打开的面板立刻关掉。入口处的默认拒绝由 {@link PmClientState#isAdmin()} 负责。
     */
    @Override
    public void tick() {
        super.tick();
        if (PmClientState.knownNonAdmin()) {
            onClose();
            return;
        }
        // 「对局 / 事件」是活数据：面板开着且前台是它们时每秒要一份新快照。
        // 不订阅 CCNR-RP 的推送包（那要依赖对方的协议细节），代价只是面板打开期间每秒一个空载请求。
        if (LIVE_TABS.contains(tabs.get(activeTab).id()) && ++matchPollCounter >= MATCH_POLL_TICKS) {
            matchPollCounter = 0;
            ClientMatchState.request();
        }
    }

    private static String tr(String key) {
        return Component.translatable(key).getString();
    }
}
