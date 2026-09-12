/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.pm.client;

import com.ccnrcom.pm.client.hud.BoardAvatarLayout;
import com.ccnrcom.pm.client.hud.NoticeCardLayout;
import com.ccnrcom.pm.client.hud.TicketNoticeOverlay;
import com.ccnrcom.pm.client.ui.PmButton;
import com.ccnrcom.pm.client.ui.PmPopupLayout;
import com.ccnrcom.pm.client.ui.PmScrollbar;
import com.ccnrcom.pm.client.ui.PmTheme;
import com.ccnrcom.pm.integration.ProfessionRef;
import com.ccnrcom.pm.network.PmChannel;
import com.ccnrcom.pm.network.PmPackets;
import com.ccnrcom.pm.ticket.TicketBoard;
import com.ccnrcom.pm.ticket.TicketStatus;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * 「按住 P 后鼠标出来」的交互看板。
 *
 * <h2>为什么必须是 Screen 而不是 HUD 浮层</h2>
 * 看板卡片要能**点击**（认领 / 关闭 / 头像上的玩家动作）。HUD 浮层在无界面时鼠标被相机抓取、
 * 没有光标坐标，根本无法命中判断；而 Screen 一打开就自动释放鼠标，且自带事件分发。
 * 因此：**只读看板是浮层（{@code TicketNoticeOverlay}），交互看板是 Screen（本类）**，
 * 两者用同一套 {@link NoticeCardLayout} 几何，切过去时卡片位置不跳。
 *
 * <h2>与只读浮层的关系（打开时必须让浮层停下来）</h2>
 * 原版 HUD 会**连 Screen 一起**画在屏幕最底层，所以只读浮层与本屏会同时画在同一位置并叠在一起。
 * 因此 {@code TicketNoticeOverlay} 在本屏打开时直接跳过绘制（见那里的注释）——
 * 「同一个看板画两遍」不是风格问题，是重影。
 *
 * <h2>透明</h2>
 * 本屏**不画背景**（不调用 {@code renderBackground}），因此游戏画面照常可见，
 * 效果就是「鼠标出来了、左上角卡片多了一排按钮」。
 *
 * <h2>版式</h2>
 * <pre>
 * ┌ 左上：活跃工单卡片（每张底部：待处理=认领/忽略，处理中=关闭/释放）
 * │
 * │                                                ┌── 右键头像弹出的快捷菜单
 * │                                                │ Alice        复制
 * │                                                │ 把玩家传送过来
 * │                                                │ 传送到该玩家
 * │                                                │ 刷出玩家
 * │                                                │ 刷出并传送至此
 * └──────────────────────────────  [打开 PM 面板 ] │ 送回阴间
 *                                 [打开 CCNR-RP ] └──
 * </pre>
 * 快捷按钮在**右下角**（用户要求），弹层永远最后绘制。
 */
public final class PmBoardScreen extends Screen {

    /** 右下角快捷按钮尺寸。 */
    private static final int BTN_H = 18;

    private static final int BTN_W = 132;

    /** 卡片底部的按钮高度。 */
    private static final int CARD_BTN_H = NoticeCardLayout.BUTTON_H;

    /** 角色选择列表滚动条 id（**本界面内唯一**）。 */
    private static final int SCROLL_ID = 91;

    /** 当前打开的交互看板（角色列表回包要投递给它；未打开时为 null）。 */
    private static volatile PmBoardScreen open;

    private final Screen parent;

    /** 当前弹层（右键菜单或角色选择器）；null 表示没有弹层。 */
    private Popup popup;

    /** 已发出「要选角色」的意图、正在等角色列表的动作 id（null = 不在等）。 */
    private String pendingRoleAction;

    /** 当前打开的交互看板（无则 null）。 */
    public static PmBoardScreen open() {
        return open;
    }

    public PmBoardScreen() {
        super(Component.translatable("ccnr_pm.board.title"));
        this.parent = Minecraft.getInstance().screen;
    }

    // ------------------------------------------------------------------
    // 弹层数据模型
    // ------------------------------------------------------------------

    /** 复制「玩家名 (UUID)」到剪贴板的行（客户端内部动作，不发包）。 */
    private static final String ROW_COPY = "__copy";

    /**
     * 弹层的一行。
     *
     * @param label   左侧文字
     * @param hint    右侧小字（标题行用来提示「点击复制」）
     * @param action  点下去要执行的动作（{@link PmPackets} 的 ACT_* 或 {@link #ROW_COPY}）
     * @param arg     动作参数（角色 id）
     * @param enabled 是否可点（标题行/空提示行不可点）
     */
    private record Row(String label, String hint, String action, String arg, boolean enabled) {}

    /** 弹层状态：是「玩家快捷菜单」还是「角色选择器」。 */
    private static final class Popup {
        /** 针对的玩家（uuid 可能为空：手输的离线关联玩家）。 */
        final String playerName;

        final String playerUuid;
        /** 锚点（鼠标位置），决定弹层往哪边展开。 */
        final int anchorX;

        final int anchorY;
        /** 角色选择器：选完后要发的动作 id。 */
        final String action;

        final List<ProfessionRef> roles;
        int scroll;

        Popup(
                String playerName,
                String playerUuid,
                int anchorX,
                int anchorY,
                String action,
                List<ProfessionRef> roles) {
            this.playerName = playerName == null ? "" : playerName;
            this.playerUuid = playerUuid == null ? "" : playerUuid;
            this.anchorX = anchorX;
            this.anchorY = anchorY;
            this.action = action == null ? "" : action;
            this.roles = roles == null ? List.of() : List.copyOf(roles);
        }

        boolean isRolePicker() {
            return !action.isBlank();
        }
    }

    // ------------------------------------------------------------------
    // 生命周期
    // ------------------------------------------------------------------

    @Override
    protected void init() {
        open = this;
        // 打开即刷新一次：确保看到的是当前权威状态，而不是浮层里可能已过期的快照
        ClientTicketBoard.request("all", 1);
    }

    @Override
    public void onClose() {
        clearState();
        Minecraft.getInstance().setScreen(parent);
    }

    /**
     * 权限被收回时（服务端补发 {@code AdminStateS2C(false)}）自动关闭 —— 看板本身也是管理界面
     * （卡片上是认领/忽略/关闭/释放，数据只有管理员才拿得到），留着它没有意义。
     * 判据用 {@link PmClientState#knownNonAdmin()}（"已确认不是"），不用"标志不为真"，理由见该类注释。
     */
    @Override
    public void tick() {
        super.tick();
        if (PmClientState.knownNonAdmin()) {
            onClose();
        }
    }

    /**
     * 界面被替换/世界卸载时的清理。
     *
     * <p>**必须单独实现**：{@code Minecraft.setScreen(其它界面)} 只会调 {@code removed()}，
     * **不会**调 {@code onClose()}（长按 P 打开管理面板走的正是这条路）。
     * 不清静态句柄的话，`PmBoardScreen.open()` 会一直指着一个已经不在屏幕上的实例，
     * 角色列表回包就会投递给它——画面早已不是它了。
     */
    @Override
    public void removed() {
        clearState();
        super.removed();
    }

    private void clearState() {
        if (open == this) open = null;
        popup = null;
        pendingRoleAction = null;
        PmScrollbar.endDrag();
    }

    /** 服务端回答的可选角色列表：只有当时还开着角色选择流程才接手。 */
    void onProfessionList(boolean available, List<ProfessionRef> roles) {
        String action = pendingRoleAction;
        if (action == null || popup == null) return; // 已关掉/已改主意：过期回包丢掉
        pendingRoleAction = null;
        if (!available) {
            // 服务端没有 CCNR-RP：直接走「没装 RP」的那条路（冒险模式），不要弹一个空列表
            sendPlayerAction(action, popup.playerName, popup.playerUuid, "");
            closePopup();
            return;
        }
        popup = new Popup(popup.playerName, popup.playerUuid, popup.anchorX, popup.anchorY, action, roles);
    }

    // ------------------------------------------------------------------
    // 绘制
    // ------------------------------------------------------------------

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        // **刻意不画背景**：交互看板的语义是「游戏画面之上浮出一层可点的卡片」
        TicketBoard board = TicketNoticeOverlay.board();
        List<TicketBoard.Card> cards = TicketNoticeOverlay.orderedCards();

        // 悬停的头像只算一次：每个头像都是「可以点的」，描一圈亮框把这件事说出来
        BoardAvatarLayout.Slot hoveredAvatar = avatarAt(mouseX, mouseY);

        int drawn = 0;
        for (TicketBoard.Card card : cards) {
            if (drawn >= NoticeCardLayout.MAX_VISIBLE) break;
            renderCard(
                    g,
                    card,
                    NoticeCardLayout.MARGIN,
                    NoticeCardLayout.cardYInteractive(drawn),
                    mouseX,
                    mouseY,
                    hoveredAvatar);
            drawn++;
        }
        int remaining = cards.size() - drawn;
        if (remaining > 0) {
            renderOverflow(g, drawn, remaining);
        }
        if (board.isEmpty()) {
            g.drawString(
                    Minecraft.getInstance().font,
                    tr("ccnr_pm.board.empty"),
                    NoticeCardLayout.MARGIN + 4,
                    NoticeCardLayout.MARGIN + 4,
                    PmTheme.TEXT_SECONDARY,
                    false);
        }

        renderShortcutButtons(g, mouseX, mouseY);

        // 弹层必须**最后**绘制：它是浮在看板之上的
        renderPopup(g, mouseX, mouseY);
    }

    /** 一张可交互卡片：只读版式的画法 + 底部一排按钮（按状态换动作）。 */
    private void renderCard(
            GuiGraphics g,
            TicketBoard.Card card,
            int px1,
            int py1,
            int mouseX,
            int mouseY,
            BoardAvatarLayout.Slot hoveredAvatar) {
        var font = Minecraft.getInstance().font;
        int h = NoticeCardLayout.cardHeightInteractive();
        int px2 = px1 + NoticeCardLayout.CARD_W;
        int py2 = py1 + h;
        boolean claimed = TicketStatus.CLAIMED.id().equals(card.status());

        g.fill(px1, py1, px2, py2, PmTheme.OVERLAY);
        PmTheme.outlined(g, px1, py1, px2, py2, PmTheme.PANEL_BORDER_BRIGHT);
        g.fill(px1, py1, px1 + 3, py2, claimed ? PmTheme.CYAN : PmTheme.statusColor(card.status()));

        int innerX1 = px1 + NoticeCardLayout.PAD;
        int innerX2 = px2 - NoticeCardLayout.PAD;

        // 标题行：左＝举报内容，右＝类别 + 带色的状态标签（与只读浮层共用同一套画法）
        TicketNoticeOverlay.renderHeaderRow(g, card, px1, py1);

        // 详细描述
        int dy = py1 + NoticeCardLayout.detailY();
        int detailLimit = py1 + NoticeCardLayout.nameY() - 1;
        List<String> lines = PmTheme.wrapText(font, card.detail(), NoticeCardLayout.innerWidth());
        boolean truncated = lines.size() > NoticeCardLayout.DETAIL_LINES;
        for (int i = 0; i < NoticeCardLayout.DETAIL_LINES && i < lines.size(); i++) {
            String line = lines.get(i);
            if (dy + 9 > detailLimit) break;
            if (truncated && i == NoticeCardLayout.DETAIL_LINES - 1) {
                int ellW = font.width("\u2026");
                line = PmTheme.clip(font, line, Math.max(0, NoticeCardLayout.innerWidth() - ellW)) + "\u2026";
            }
            g.drawString(font, line, innerX1, dy, PmTheme.TEXT_DIM, false);
            dy += NoticeCardLayout.LINE;
        }

        // 头像行（与只读浮层复用同一套画法）；悬停时已在外层算好，这里只描框
        TicketNoticeOverlay.renderAvatarRow(g, card, px1, py1);
        if (hoveredAvatar != null) {
            PmTheme.outlined(
                    g, hoveredAvatar.x(), hoveredAvatar.y(), hoveredAvatar.x2(), hoveredAvatar.y2(), PmTheme.CYAN);
        }

        renderCardActions(g, px1, py1, mouseX, mouseY, claimed, innerX1, innerX2);
    }

    /**
     * 卡片底部按钮。
     *
     * <p>**按状态换动作**（用户指定）：待处理＝认领 / 忽略；处理中＝关闭（结束工单）/ 释放（驳回）。
     * 「认领/办结/驳回」的状态机判定真正的边界在服务端，这里的禁用只是体验。
     */
    private void renderCardActions(
            GuiGraphics g, int px1, int py1, int mouseX, int mouseY, boolean claimed, int innerX1, int innerX2) {
        var font = Minecraft.getInstance().font;
        int by1 = py1 + NoticeCardLayout.CARD_H;
        int by2 = by1 + CARD_BTN_H;
        int bw = (NoticeCardLayout.CARD_W - NoticeCardLayout.PAD * 2 - 4) / 2;
        int[] left = {innerX1, by1, innerX1 + bw, by2};
        int[] right = {innerX1 + bw + 4, by1, innerX2, by2};

        if (claimed) {
            // 文案按**动作的结果状态**上色（暗底用状态色做字色，亮底走描边，见 PmButton 的说明）：
            // 关闭 → 已办结（灰蓝）；释放 → 已驳回（驳回的文字色用告警红，STATUS_REJECTED 太暗看不清）
            PmButton.draw(
                    g,
                    font,
                    left[0],
                    left[1],
                    left[2],
                    left[3],
                    tr("ccnr_pm.board.close"),
                    PmButton.Variant.PRIMARY,
                    PmButton.hit(mouseX, mouseY, left[0], left[1], left[2], left[3]),
                    true,
                    PmTheme.statusColor(TicketStatus.CLOSED.id()));
            PmButton.draw(
                    g,
                    font,
                    right[0],
                    right[1],
                    right[2],
                    right[3],
                    tr("ccnr_pm.board.release"),
                    PmButton.Variant.DANGER,
                    PmButton.hit(mouseX, mouseY, right[0], right[1], right[2], right[3]),
                    true,
                    PmTheme.RED_LINE);
            return;
        }

        // 认领 → 处理中（状态色＝亮白，正好就是 PRIMARY 的亮底，因此文字保持深色、描边承载状态色）；
        // 忽略与工单状态无关，不上状态色。
        PmButton.draw(
                g,
                font,
                left[0],
                left[1],
                left[2],
                left[3],
                tr("ccnr_pm.board.claim"),
                PmButton.Variant.PRIMARY,
                PmButton.hit(mouseX, mouseY, left[0], left[1], left[2], left[3]),
                true,
                PmTheme.statusColor(TicketStatus.CLAIMED.id()));
        PmButton.draw(
                g,
                font,
                right[0],
                right[1],
                right[2],
                right[3],
                tr("ccnr_pm.board.ignore"),
                PmButton.Variant.SECONDARY,
                PmButton.hit(mouseX, mouseY, right[0], right[1], right[2], right[3]),
                true);
    }

    private void renderOverflow(GuiGraphics g, int drawn, int remaining) {
        var font = Minecraft.getInstance().font;
        int x = NoticeCardLayout.MARGIN;
        int y = NoticeCardLayout.cardYInteractive(drawn);
        String text = tr("ccnr_pm.board.more", Integer.toString(remaining));
        int w = font.width(text) + 10;
        g.fill(x, y, x + w, y + 12, PmTheme.OVERLAY);
        PmTheme.outlined(g, x, y, x + w, y + 12, PmTheme.PANEL_BORDER);
        g.drawString(font, text, x + 6, y + 2, PmTheme.TEXT_SECONDARY, false);
    }

    // ------------------------------------------------------------------
    // 右下角快捷按钮
    // ------------------------------------------------------------------

    /** 右下角：打开 PM 管理面板（始终有）。 */
    private int[] pmButtonRect() {
        int y = height - NoticeCardLayout.MARGIN - BTN_H;
        int x2 = width - NoticeCardLayout.MARGIN;
        return new int[] {x2 - BTN_W, y, x2, y + BTN_H};
    }

    /** 右下角：打开 CCNR-RP 管理面板（装了才有），排在 PM 按钮**上方**。 */
    private int[] rpButtonRect() {
        int[] pm = pmButtonRect();
        return new int[] {pm[0], pm[1] - BTN_H - 2, pm[2], pm[1] - 2};
    }

    private void renderShortcutButtons(GuiGraphics g, int mouseX, int mouseY) {
        var font = Minecraft.getInstance().font;
        // 「打开管理面板」按钮只在有管理权限时画：不给没权限的人看到一个点了没反应的按钮
        // （看板本身已经进不来，这里是纵深防御——万一将来有别的路径打开看板）
        if (PmClientState.isAdmin()) {
            int[] pm = pmButtonRect();
            PmButton.draw(
                    g,
                    font,
                    pm[0],
                    pm[1],
                    pm[2],
                    pm[3],
                    tr("ccnr_pm.board.open_panel"),
                    PmButton.Variant.PRIMARY,
                    PmButton.hit(mouseX, mouseY, pm[0], pm[1], pm[2], pm[3]),
                    true);
        }

        if (!RpPanel.isAvailable()) return;
        int[] rp = rpButtonRect();
        PmButton.draw(
                g,
                font,
                rp[0],
                rp[1],
                rp[2],
                rp[3],
                tr("ccnr_pm.board.open_rp_panel"),
                PmButton.Variant.SECONDARY,
                PmButton.hit(mouseX, mouseY, rp[0], rp[1], rp[2], rp[3]),
                true);
    }

    // ------------------------------------------------------------------
    // 弹层：右键快捷菜单 / 角色选择器
    // ------------------------------------------------------------------

    /** 弹层当前应有的行。 */
    private List<Row> popupRows() {
        List<Row> rows = new ArrayList<>();
        Popup p = popup;
        if (p == null) return rows;

        if (!p.isRolePicker()) {
            // 标题行：玩家名 + 右侧「复制」小字；点它把「名字 (UUID)」放进剪贴板
            rows.add(new Row(p.playerName, tr("ccnr_pm.board.menu.copy"), ROW_COPY, "", !p.playerName.isBlank()));
            rows.add(new Row(tr("ccnr_pm.board.menu.tp_here"), null, PmPackets.ACT_TP_HERE, "", true));
            rows.add(new Row(tr("ccnr_pm.board.menu.tp_to"), null, PmPackets.ACT_TP_TO, "", true));
            rows.add(new Row(tr("ccnr_pm.board.menu.spawn"), null, PmPackets.ACT_SPAWN, "", true));
            rows.add(new Row(tr("ccnr_pm.board.menu.spawn_tp"), null, PmPackets.ACT_SPAWN_TP, "", true));
            rows.add(new Row(tr("ccnr_pm.board.menu.observer"), null, PmPackets.ACT_OBSERVER, "", true));
            return rows;
        }

        rows.add(new Row(tr("ccnr_pm.board.role.title", p.playerName), null, "", "", false));
        if (p.roles.isEmpty()) {
            // 服务端说有 RP 却一个角色都没有：如实显示，不假装点了会有反应
            rows.add(new Row(tr("ccnr_pm.board.role.empty"), null, "", "", false));
            return rows;
        }
        for (ProfessionRef role : p.roles) {
            // 右侧小字显示职业 id（与名字相同时不重复显示），方便管理员对上命令里的 id
            String hint = role.id().equals(role.name()) ? null : role.id();
            rows.add(new Row(role.name(), hint, p.action, role.id(), true));
        }
        return rows;
    }

    /** 弹层矩形（屏幕坐标，已夹进屏幕内）。 */
    private int[] popupRect() {
        List<Row> rows = popupRows();
        var font = Minecraft.getInstance().font;
        int widest = 0;
        for (Row r : rows) {
            int w = font.width(r.label());
            if (r.hint() != null) w += font.width(r.hint()) + 12;
            widest = Math.max(widest, w);
        }
        int w = PmPopupLayout.width(widest, width);
        return PmPopupLayout.anchor(popup.anchorX, popup.anchorY, w, Math.max(1, rows.size()), width, height);
    }

    /** 角色列表是否需要滚动（超过可视行数）。 */
    private boolean popupScrollable(List<Row> rows) {
        return rows.size() > popupVisibleRows(rows.size());
    }

    private int popupMaxScroll(List<Row> rows) {
        return Math.max(0, rows.size() - popupVisibleRows(rows.size()));
    }

    /**
     * 弹层当前能画几行： {@code MAX_ROWS} 与**屏幕高度**两者取小。
     *
     * <p>屏幕很矮时必须以屏幕为准，否则弹层下缘会跑到屏幕外——那几行既看不到也点不到。
     */
    private int popupVisibleRows(int total) {
        return PmPopupLayout.visibleRows(total, height);
    }

    private void renderPopup(GuiGraphics g, int mouseX, int mouseY) {
        Popup p = popup;
        if (p == null) return;
        List<Row> rows = popupRows();
        if (rows.isEmpty()) {
            popup = null;
            return;
        }
        var font = Minecraft.getInstance().font;
        int[] r = popupRect();
        int visible = popupVisibleRows(rows.size());
        p.scroll = Math.max(0, Math.min(p.scroll, popupMaxScroll(rows)));

        PmTheme.popupPanel(g, r[0], r[1], r[2], r[3]);

        boolean scrollable = popupScrollable(rows);
        int textRight = r[2] - 4 - (scrollable ? PmScrollbar.WIDTH + 1 : 0);
        for (int i = 0; i < visible; i++) {
            Row row = rows.get(p.scroll + i);
            int ry1 = r[1] + i * PmPopupLayout.ROW_H;
            int ry2 = ry1 + PmPopupLayout.ROW_H;
            boolean hovered = row.enabled() && mouseX >= r[0] && mouseX < r[2] && mouseY >= ry1 && mouseY < ry2;
            // 标题行用 popupRow(current=true)：亮底＋深字，一眼看出「这是你点的那个玩家」
            // （角色选择器的首行是列表标题，不该抢眼，所以不标 current）
            boolean current = !p.isRolePicker() && i == 0 && p.scroll == 0;
            PmTheme.popupRow(g, r[0] + 1, ry1, textRight, ry2, current, hovered);

            int ink = row.enabled() ? PmTheme.popupRowText(current, hovered) : PmTheme.TEXT_DISABLED;
            g.drawString(font, PmTheme.clip(font, row.label(), textRight - (r[0] + 5)), r[0] + 5, ry1 + 2, ink, false);

            if (row.hint() != null) {
                int hw = font.width(row.hint());
                int hx = Math.max(r[0] + 5, textRight - 4 - hw);
                g.drawString(font, row.hint(), hx, ry1 + 2, current ? PmTheme.ACCENT_TEXT : PmTheme.TEXT_DIM, false);
            }
        }

        if (scrollable) {
            PmScrollbar.draw(g, r[2] - 1 - PmScrollbar.WIDTH, r[1], r[3], rows.size(), visible, p.scroll);
        }
    }

    private void openMenu(BoardAvatarLayout.Slot slot, int anchorX, int anchorY) {
        popup = new Popup(slot.name(), slot.uuid(), anchorX, anchorY, "", List.of());
        pendingRoleAction = null;
    }

    private void closePopup() {
        popup = null;
        pendingRoleAction = null;
        PmScrollbar.endDrag();
    }

    /** 弹层点击：命中就执行，点在别处只关弹层（**不穿透**到下层卡片）。 */
    private boolean popupClicked(double mx, double my, int button) {
        Popup p = popup;
        if (p == null) return false;
        List<Row> rows = popupRows();
        int[] r = popupRect();

        if (popupScrollable(rows)) {
            int visible = popupVisibleRows(rows.size());
            int next = PmScrollbar.clickV(
                    mx,
                    my,
                    r[2] - 1 - PmScrollbar.WIDTH,
                    r[2] - 1,
                    r[1],
                    r[3],
                    rows.size(),
                    visible,
                    p.scroll,
                    SCROLL_ID);
            if (next >= 0) {
                p.scroll = Math.max(0, Math.min(next, popupMaxScroll(rows)));
                return true;
            }
        }

        int idx = PmPopupLayout.rowAt(r[1], r[3], my, rows.size());
        if (button == 0 && mx >= r[0] && mx < r[2] && idx >= 0) {
            int row = p.scroll + idx;
            if (row < rows.size() && rows.get(row).enabled()) {
                activate(rows.get(row));
                return true;
            }
            return true; // 点不可点的行（标题/空提示）同样吞掉，不穿透
        }
        closePopup();
        return true;
    }

    /** 执行一行：复制、选角色、或把玩家动作发给服务端。 */
    private void activate(Row row) {
        Popup p = popup;
        if (p == null) return;
        if (ROW_COPY.equals(row.action())) {
            copyNameAndUuid();
            closePopup();
            return;
        }
        if (p.isRolePicker()) {
            // 角色选择器：arg 就是选中的角色 id
            sendPlayerAction(row.action(), p.playerName, p.playerUuid, row.arg());
            closePopup();
            return;
        }
        boolean spawnAction = PmPackets.ACT_SPAWN.equals(row.action()) || PmPackets.ACT_SPAWN_TP.equals(row.action());
        if (spawnAction && RpPanel.isAvailable()) {
            // 装了 CCNR-RP：先问服务端有哪些角色，再弹滚动选择列表
            pendingRoleAction = row.action();
            PmChannel.sendToServer(new PmPackets.RequestProfessionsC2S());
            return;
        }
        sendPlayerAction(row.action(), p.playerName, p.playerUuid, "");
        closePopup();
    }

    /**
     * 复制「玩家名 (UUID)」到剪贴板。
     *
     * <p>没有 UUID（手输的离线关联玩家）时只复制名字——凭空编一个 UUID 粘到命令里只会更糟。
     * 复制完在动作栏提示一句：剪贴板是**看不见**的，没有反馈等于让人不确定到底复制了没。
     */
    private void copyNameAndUuid() {
        Popup p = popup;
        if (p == null || p.playerName.isBlank()) return;
        String text = p.playerUuid.isBlank() ? p.playerName : p.playerName + " (" + p.playerUuid + ")";
        Minecraft mc = Minecraft.getInstance();
        mc.keyboardHandler.setClipboard(text);
        if (mc.player != null) {
            mc.player.displayClientMessage(Component.translatable("ccnr_pm.board.copied", text), true);
        }
    }

    /** 只发意图；玩家是否在线、有没有权限，全部由服务端重新判定。 */
    private static void sendPlayerAction(String action, String name, String uuid, String arg) {
        PmChannel.sendToServer(new PmPackets.PlayerActionC2S(action, uuid, name, arg));
    }

    // ------------------------------------------------------------------
    // 命中
    // ------------------------------------------------------------------

    /** 鼠标下的头像槽位（只看画出来的那几张卡片，与渲染顺序一致）。 */
    private BoardAvatarLayout.Slot avatarAt(double mx, double my) {
        List<TicketBoard.Card> cards = TicketNoticeOverlay.orderedCards();
        for (int i = 0; i < cards.size() && i < NoticeCardLayout.MAX_VISIBLE; i++) {
            List<BoardAvatarLayout.Slot> slots = BoardAvatarLayout.slots(
                    cards.get(i), NoticeCardLayout.MARGIN, NoticeCardLayout.cardYInteractive(i));
            BoardAvatarLayout.Slot hit = BoardAvatarLayout.hit(slots, mx, my);
            if (hit != null) return hit;
        }
        return null;
    }

    // ------------------------------------------------------------------
    // 输入
    // ------------------------------------------------------------------

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        // 弹层打开时吞掉一切点击（docs/03 §5：点到别处只关弹层，不穿透）
        if (popupClicked(mx, my, button)) return true;
        if (button != 0 && button != 1) return super.mouseClicked(mx, my, button);

        // 头像：左键＝把玩家传送过来，右键＝打开快捷菜单
        BoardAvatarLayout.Slot slot = avatarAt(mx, my);
        if (slot != null) {
            if (button == 0) {
                sendPlayerAction(PmPackets.ACT_TP_HERE, slot.name(), slot.uuid(), "");
                return true;
            }
            openMenu(slot, (int) mx, (int) my);
            return true;
        }

        // 卡片上的按钮（按状态：认领/忽略 或 关闭/释放）
        List<TicketBoard.Card> cards = TicketNoticeOverlay.orderedCards();
        for (int i = 0; i < cards.size() && i < NoticeCardLayout.MAX_VISIBLE; i++) {
            TicketBoard.Card card = cards.get(i);
            int px1 = NoticeCardLayout.MARGIN;
            int py1 = NoticeCardLayout.cardYInteractive(i);
            int innerX1 = px1 + NoticeCardLayout.PAD;
            int innerX2 = px1 + NoticeCardLayout.CARD_W - NoticeCardLayout.PAD;
            int by1 = py1 + NoticeCardLayout.CARD_H;
            int by2 = by1 + CARD_BTN_H;
            int bw = (NoticeCardLayout.CARD_W - NoticeCardLayout.PAD * 2 - 4) / 2;
            boolean claimed = TicketStatus.CLAIMED.id().equals(card.status());

            // 处理中：关闭（结束工单）/ 释放（驳回）；待处理：认领 / 忽略
            String left = claimed ? "close" : "claim";
            String right = claimed ? "reject" : "ignore";
            if (button == 0 && PmButton.hit(mx, my, innerX1, by1, innerX1 + bw, by2)) {
                sendTicketAction(card, left);
                return true;
            }
            if (button == 0 && PmButton.hit(mx, my, innerX1 + bw + 4, by1, innerX2, by2)) {
                sendTicketAction(card, right);
                return true;
            }
        }

        // 右下角快捷按钮
        if (button == 0) {
            int[] pm = pmButtonRect();
            // 与渲染同一判据（按钮没画就不该能点到）；权威判据仍在服务端
            if (PmClientState.isAdmin() && PmButton.hit(mx, my, pm[0], pm[1], pm[2], pm[3])) {
                Minecraft.getInstance().setScreen(new PmAdminScreen());
                return true;
            }
            if (RpPanel.isAvailable()) {
                int[] rp = rpButtonRect();
                if (PmButton.hit(mx, my, rp[0], rp[1], rp[2], rp[3])) {
                    RpPanel.open();
                    return true;
                }
            }
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        // 弹层打开时滚轮只滚弹层，不让下层跟着滚（docs/03 §5）
        Popup p = popup;
        if (p != null) {
            List<Row> rows = popupRows();
            if (popupScrollable(rows)) {
                int max = popupMaxScroll(rows);
                p.scroll = Math.max(0, Math.min(max, p.scroll + (delta > 0 ? -1 : 1)));
            }
            return true;
        }
        return super.mouseScrolled(mx, my, delta);
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        if (popup != null && PmScrollbar.isDragging()) {
            int next = PmScrollbar.dragV(SCROLL_ID, my);
            if (next >= 0) {
                popup.scroll = Math.max(0, Math.min(next, popupMaxScroll(popupRows())));
            }
            return true;
        }
        return super.mouseDragged(mx, my, button, dx, dy);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        PmScrollbar.endDrag();
        return super.mouseReleased(mx, my, button);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // Esc 先关弹层，再关界面——绝不能一次 Esc 把整个看板关掉（docs/03 §5）
        if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE && popup != null) {
            closePopup();
            return true;
        }
        // Esc 已在 Screen 里默认关闭；这里额外允许再按一次 P 关闭（与「按住 P 打开」对称）
        if (PmClientSetup.OPEN_PANEL.matches(keyCode, scanCode)) {
            onClose();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    /** 工单动作：只发意图，等服务端回推权威看板（客户端不自己改本地状态）。 */
    private void sendTicketAction(TicketBoard.Card card, String action) {
        PmChannel.sendToServer(new PmPackets.TicketActionC2S(action, card.id(), ""));
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private static String tr(String key, Object... args) {
        return args.length == 0
                ? Component.translatable(key).getString()
                : Component.translatable(key, args).getString();
    }
}
