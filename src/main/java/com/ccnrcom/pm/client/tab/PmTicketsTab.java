/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.pm.client.tab;

import com.ccnrcom.pm.client.ClientTicketBoard;
import com.ccnrcom.pm.client.PmAdminScreen;
import com.ccnrcom.pm.client.ui.PmButton;
import com.ccnrcom.pm.client.ui.PmNames;
import com.ccnrcom.pm.client.ui.PmScrollbar;
import com.ccnrcom.pm.client.ui.PmTab;
import com.ccnrcom.pm.client.ui.PmTheme;
import com.ccnrcom.pm.network.PmChannel;
import com.ccnrcom.pm.network.PmPackets;
import com.ccnrcom.pm.ticket.TicketId;
import com.ccnrcom.pm.ticket.TicketPage;
import com.ccnrcom.pm.ticket.TicketStatus;
import com.ccnrcom.pm.util.TimeText;
import java.util.List;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

/**
 * 「工单管理」页签。
 *
 * <h2>结构</h2>
 * 左侧工单列表 → 右侧选中工单详情 → 底部动作按钮（认领 / 办结 / 驳回）+ 备注框。
 *
 * <h2>为什么**没有**筛选行（用户明确要求去掉）</h2>
 * 早期版本顶部有一排「全部/待处理/处理中/已办结/已驳回」。用户指出那是多余的一层：
 * 管理员要的是「按该处理的顺序排好」，而不是自己去筛。因此改为**服务端按可操作性排序**
 * （见 {@link com.ccnrcom.pm.ticket.TicketSort}：我认领的置顶 → 待处理 → 别人认领的 → 收尾态），
 * 列表顶部就是最该处理的，不需要再筛。
 *
 * <h2>为什么列表首行不再是编号（用户明确要求）</h2>
 * 之前每行首行是短号，用户反馈「为什么显示编号，找不到东西」——编号无法帮人认出这是哪件事。
 * 现在**首行是举报内容**（一眼看出是什么事），次行是类别/状态/关系；
 * 编号只在右侧详情里作为「引用用」的字段出现。
 *
 * <h2>服务端权威</h2>
 * 面板**从不自己改本地列表**。点「认领」只发动作包，成功后由服务端回推权威快照，
 * 界面照新快照重画。排序也在服务端做，客户端只按顺序画，避免两处排序规则不一致。
 */
public final class PmTicketsTab implements PmTab {

    private static final int ROW_H = 22;
    private static final int HDR_H = 14;
    private static final int BTN_H = 18;
    private static final int SCROLL_ID = 71; // 面板内唯一

    private final PmAdminScreen screen;

    private int x1;
    private int y1;
    private int x2;
    private int y2;

    private int scroll;
    private int selected = -1;
    private EditBox noteBox;

    private int[] listRect = new int[0];
    private int[] detailRect = new int[0];
    private int[] actionRects = new int[0];

    public PmTicketsTab(PmAdminScreen screen) {
        this.screen = screen;
    }

    @Override
    public String id() {
        return "tickets";
    }

    private List<TicketPage.Entry> rows() {
        return ClientTicketBoard.page().map(TicketPage::tickets).orElse(List.of());
    }

    private Optional<TicketPage.Entry> selectedEntry() {
        List<TicketPage.Entry> list = rows();
        if (selected < 0 || selected >= list.size()) return Optional.empty();
        return Optional.of(list.get(selected));
    }

    // ------------------------------------------------------------------
    // 布局
    // ------------------------------------------------------------------

    @Override
    public void rebuild(int x1, int y1, int x2, int y2) {
        this.x1 = x1;
        this.y1 = y1;
        this.x2 = x2;
        this.y2 = y2;

        int bodyTop = y1;
        int bottom = y2 - BTN_H - 6;

        int listW = Math.max(160, (int) ((x2 - x1) * 0.52));
        listRect = new int[] {x1, bodyTop, Math.min(x1 + listW, x2 - 80), bottom};
        detailRect = new int[] {listRect[2] + 8, bodyTop, x2, bottom};

        int bw = Math.max(60, ((x2 - x1) - 16) / 3);
        actionRects = new int[12];
        for (int i = 0; i < 3; i++) {
            int bx = x1 + i * (bw + 8);
            actionRects[i * 4] = bx;
            actionRects[i * 4 + 1] = y2 - BTN_H;
            actionRects[i * 4 + 2] = Math.min(bx + bw, x2);
            actionRects[i * 4 + 3] = y2;
        }

        // 选中项可能因刷新而消失：**先夹紧索引**，备注框的可编辑状态依赖它
        if (selected >= rows().size()) selected = rows().isEmpty() ? -1 : rows().size() - 1;

        // 备注输入框：**必须把已输入的备注接过来**——切换选中行会触发一次重建，
        // 若直接置空，管理员打完备注一点别处就白打了
        String keepNote = noteBox == null ? "" : noteBox.getValue();
        int nw = Math.max(60, detailRect[2] - detailRect[0] - 4);
        noteBox =
                new EditBox(Minecraft.getInstance().font, detailRect[0], detailRect[3] - 22, nw, 18, Component.empty());
        noteBox.setMaxLength(128);
        noteBox.setValue(keepNote);
        noteBox.setEditable(selectedEntry().isPresent());
        screen.addPanelWidget(noteBox);
    }

    private int visibleRows() {
        if (listRect.length == 0) return 1;
        return Math.max(1, (listRect[3] - listRect[1] - HDR_H) / ROW_H);
    }

    // ------------------------------------------------------------------
    // 绘制
    // ------------------------------------------------------------------

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY) {
        renderList(g, mouseX, mouseY);
        renderDetail(g, mouseX, mouseY);
        renderActions(g, mouseX, mouseY);
    }

    private void renderList(GuiGraphics g, int mouseX, int mouseY) {
        if (listRect.length < 4) return;
        var font = Minecraft.getInstance().font;
        int lx1 = listRect[0];
        int ly1 = listRect[1];
        int lx2 = listRect[2];
        int ly2 = listRect[3];

        PmTheme.listPanel(g, lx1 - 2, ly1 - 4, lx2 + 2, ly2 + 2);
        g.drawString(font, PmTheme.section("TICKETS"), lx1 + 2, ly1 + 2, PmTheme.TEXT_DIM, false);
        PmTheme.listHeaderRule(g, lx1, lx2, ly1 + HDR_H - 1);

        List<TicketPage.Entry> list = rows();
        int visible = visibleRows();
        int maxScroll = Math.max(0, list.size() - visible);
        scroll = Math.max(0, Math.min(scroll, maxScroll));

        if (list.isEmpty()) {
            String msg = ClientTicketBoard.hasData() ? tr("ccnr_pm.panel.empty") : tr("ccnr_pm.panel.loading");
            g.drawString(font, msg, lx1 + 4, ly1 + HDR_H + 6, PmTheme.TEXT_DISABLED, false);
            return;
        }

        int textW = (lx2 - 6) - (lx1 + 4);
        for (int i = 0; i < visible; i++) {
            int idx = scroll + i;
            if (idx >= list.size()) break;
            TicketPage.Entry e = list.get(idx);
            int ry1 = ly1 + HDR_DETAIL() + i * ROW_H;
            int ry2 = ry1 + ROW_H;
            boolean isSel = idx == selected;
            boolean hovered = mouseX >= lx1 && mouseX < lx2 && mouseY >= ry1 && mouseY < ry2;
            if (isSel) {
                PmTheme.selectedBar(g, lx1, ry1, lx2, ry2, 0f);
            } else {
                PmTheme.listRow(g, lx1, ry1, lx2, ry2, idx, hovered);
            }
            // 选中行是亮底 → 必须用深色字（否则白底白字）
            int ink = isSel ? PmTheme.ACCENT_TEXT : PmTheme.TEXT_PRIMARY;
            int dim = isSel ? PmTheme.ACCENT_TEXT : PmTheme.TEXT_SECONDARY;

            // 首行：举报内容（一眼认出是什么事）——**不是编号**
            g.drawString(font, PmTheme.clip(font, e.content(), textW), lx1 + 4, ry1 + 3, ink, false);

            // 次行：我认领的优先标记 + 类别 + **带色状态** + 关系。
            // 状态词必须留得住（用户要求「状态文字要能分辨」），所以分三段画、并给状态预留宽度：
            // 前缀被裁也要保住状态；类别与关系是最先让位的（详情里都还有）。
            String status = PmNames.statusName(e.status());
            StringBuilder prefix = new StringBuilder();
            if (isMine(e)) prefix.append(tr("ccnr_pm.panel.mine")).append(" · ");
            prefix.append(PmNames.categoryName(e.category())).append(" · ");
            StringBuilder suffix = new StringBuilder(" · ").append(e.reporter());
            if (!e.targets().isBlank()) suffix.append(" → ").append(e.targets());

            int statusW = font.width(status);
            int suffixW = font.width(suffix.toString());
            int prefixMax = Math.max(0, textW - statusW - Math.min(suffixW, textW / 3));
            String prefixShown = PmTheme.clip(font, prefix.toString(), prefixMax);
            int mx = lx1 + 4;
            g.drawString(font, prefixShown, mx, ry1 + 12, dim, false);
            mx += font.width(prefixShown);

            int leftForStatus = (lx2 - 6) - mx;
            if (leftForStatus > 0) {
                String statusShown = PmTheme.clip(font, status, leftForStatus);
                // 亮底（选中行）必须深字，因此选中行不给状态上色——那条硬性约束优先于配色
                g.drawString(
                        font,
                        statusShown,
                        mx,
                        ry1 + 12,
                        isSel ? PmTheme.ACCENT_TEXT : PmTheme.statusColor(e.status()),
                        false);
                mx += font.width(statusShown);
            }

            int leftForSuffix = (lx2 - 6) - mx;
            if (leftForSuffix > 0) {
                g.drawString(font, PmTheme.clip(font, suffix.toString(), leftForSuffix), mx, ry1 + 12, dim, false);
            }
        }
        PmScrollbar.draw(g, lx2 - 6, ly1 + HDR_DETAIL(), ly2, list.size(), visible, scroll);
    }

    /** 是否是我认领的（用于给列表行加标记，让置顶的卡片一眼可辨）。 */
    private boolean isMine(TicketPage.Entry e) {
        var self = Minecraft.getInstance().player;
        return self != null
                && !e.handler().isBlank()
                && self.getGameProfile().getName().equalsIgnoreCase(e.handler());
    }

    private void renderDetail(GuiGraphics g, int mouseX, int mouseY) {
        if (detailRect.length < 4) return;
        var font = Minecraft.getInstance().font;
        int dx1 = detailRect[0];
        int dy1 = detailRect[1];
        int dx2 = detailRect[2];
        int dy2 = detailRect[3];
        int w = dx2 - dx1;

        PmTheme.listPanel(g, dx1 - 2, dy1 - 4, dx2 + 2, dy2 + 2);
        g.drawString(font, PmTheme.section("DETAIL"), dx1 + 2, dy1 + 2, PmTheme.TEXT_DIM, false);
        PmTheme.listHeaderRule(g, dx1, dx2, dy1 + HDR_DETAIL() - 1);

        Optional<TicketPage.Entry> sel = selectedEntry();
        if (sel.isEmpty()) {
            g.drawString(
                    font, tr("ccnr_pm.panel.select_hint"), dx1 + 4, dy1 + HDR_DETAIL(), PmTheme.TEXT_DISABLED, false);
            return;
        }
        TicketPage.Entry e = sel.get();
        int y = dy1 + HDR_DETAIL();

        // 正文（举报内容）优先显示，且用亮色——这是管理员要读的东西
        y = drawWrapped(g, dx1, y, w, dy2 - 34, e.content(), PmTheme.TEXT_PRIMARY);
        if (!e.detail().isBlank()) {
            y = drawWrapped(g, dx1, y + 2, w, dy2 - 34, e.detail(), PmTheme.TEXT_SECONDARY);
        }
        // 编号只在这里出现（用于在命令里引用工单），且放在末尾、用暗色
        y = drawField(g, dx1, y + 2, w, tr("ccnr_pm.panel.field.id"), TicketId.label(e.id()), PmTheme.TEXT_DIM);
        // 提交时间：工单是新是旧直接决定受理优先级，管理员要能一眼看到（用户反馈「查看工单看不到提交时间」）
        y = drawField(
                g, dx1, y, w, tr("ccnr_pm.panel.field.created"), TimeText.format(e.createdAt()), PmTheme.TEXT_DIM);
        y = drawField(
                g, dx1, y, w, tr("ccnr_pm.panel.field.category"), PmNames.categoryName(e.category()), PmTheme.TEXT_DIM);
        y = drawField(
                g,
                dx1,
                y,
                w,
                tr("ccnr_pm.panel.field.status"),
                PmNames.statusName(e.status()),
                PmTheme.statusColor(e.status()));
        y = drawField(g, dx1, y, w, tr("ccnr_pm.panel.field.reporter"), e.reporter(), PmTheme.TEXT_DIM);
        if (!e.targets().isBlank()) {
            y = drawField(g, dx1, y, w, tr("ccnr_pm.panel.field.targets"), e.targets(), PmTheme.TEXT_DIM);
        }
        if (!e.handler().isBlank()) {
            drawField(g, dx1, y, w, tr("ccnr_pm.panel.field.handler"), e.handler(), PmTheme.TEXT_DIM);
        }
    }

    private static int HDR_DETAIL() {
        return HDR_H + 4;
    }

    private int drawField(GuiGraphics g, int dx1, int y, int w, String label, String value, int ink) {
        var font = Minecraft.getInstance().font;
        if (y + 9 > detailRect[3] - 24) return y;
        g.drawString(font, label, dx1 + 4, y, PmTheme.TEXT_DIM, false);
        int lx = dx1 + 4 + font.width(label) + 4;
        g.drawString(font, PmTheme.clip(font, value, Math.max(10, (dx1 + w - 4) - lx)), lx, y, ink, false);
        return y + 11;
    }

    private int drawWrapped(GuiGraphics g, int dx1, int y, int w, int limitY, String text, int ink) {
        var font = Minecraft.getInstance().font;
        for (String line : PmTheme.wrapText(font, text == null ? "" : text, w - 8)) {
            if (y + 9 > limitY) return y;
            g.drawString(font, line, dx1 + 4, y, ink, false);
            y += 11;
        }
        return y;
    }

    private void renderActions(GuiGraphics g, int mouseX, int mouseY) {
        if (actionRects.length < 12) return;
        var font = Minecraft.getInstance().font;
        Optional<TicketPage.Entry> sel = selectedEntry();
        boolean hasSel = sel.isPresent();
        String status = sel.map(TicketPage.Entry::status).orElse("");

        // 认领只对「待处理」有意义；办结/驳回只对「未办结」有意义。
        // 这层禁用只是体验，真正的判定在服务端（被改造过的客户端发过来的动作照样会被拒）
        boolean canClaim = hasSel && TicketStatus.OPEN.id().equals(status);
        boolean canResolve = hasSel && ("open".equals(status) || "claimed".equals(status));

        String[] labels = {
            tr("ccnr_pm.panel.action.claim"), tr("ccnr_pm.panel.action.close"), tr("ccnr_pm.panel.action.reject")
        };
        boolean[] enabled = {canClaim, canResolve, canResolve};
        PmButton.Variant[] variants = {PmButton.Variant.SECONDARY, PmButton.Variant.PRIMARY, PmButton.Variant.DANGER};
        // 文案按**动作的结果状态**上色（与交互看板的卡片按钮同一套语义，见 PmButton 的重载）：
        // 认领→处理中（亮色，暗底上就是字色）· 办结→已办结（灰蓝）· 驳回→告警红（STATUS_REJECTED 太暗看不清）
        int[] statusInks = {
            PmTheme.statusColor(TicketStatus.CLAIMED.id()),
            PmTheme.statusColor(TicketStatus.CLOSED.id()),
            PmTheme.RED_LINE
        };

        for (int i = 0; i < 3; i++) {
            int[] r = {actionRects[i * 4], actionRects[i * 4 + 1], actionRects[i * 4 + 2], actionRects[i * 4 + 3]};
            boolean hovered = enabled[i] && PmButton.hit(mouseX, mouseY, r[0], r[1], r[2], r[3]);
            PmButton.draw(g, font, r[0], r[1], r[2], r[3], labels[i], variants[i], hovered, enabled[i], statusInks[i]);
        }

        String err = ClientTicketBoard.errorKey();
        if (!err.isBlank()) {
            g.drawString(font, PmTheme.clip(font, tr(err), x2 - x1), x1, y2 + 2, PmTheme.RED_LINE, false);
        }
    }

    // ------------------------------------------------------------------
    // 输入
    // ------------------------------------------------------------------

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (button != 0) return false;

        // 列表选中
        if (listRect.length == 4
                && mx >= listRect[0]
                && mx < listRect[2]
                && my >= listRect[1] + HDR_DETAIL()
                && my < listRect[3]) {
            int row = (int) (my - (listRect[1] + HDR_DETAIL())) / ROW_H;
            int idx = scroll + row;
            if (idx >= 0 && idx < rows().size() && selected != idx) {
                selected = idx;
                // 选中变了 → 备注框可用性也要跟着变，走一次重建
                screen.refreshWidgets();
            }
            return true;
        }

        // 动作按钮
        String[] actions = {"claim", "close", "reject"};
        for (int i = 0; i < 3; i++) {
            int[] r = {actionRects[i * 4], actionRects[i * 4 + 1], actionRects[i * 4 + 2], actionRects[i * 4 + 3]};
            if (PmButton.hit(mx, my, r[0], r[1], r[2], r[3])) {
                sendAction(actions[i]);
                return true;
            }
        }
        return false;
    }

    /** 把动作发给服务端；**不改本地列表**——等服务端回推权威快照。 */
    private void sendAction(String action) {
        Optional<TicketPage.Entry> sel = selectedEntry();
        if (sel.isEmpty()) return;
        ClientTicketBoard.clearError();
        String note = noteBox == null ? "" : noteBox.getValue();
        PmChannel.sendToServer(new PmPackets.TicketActionC2S(action, sel.get().id(), note));
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        List<TicketPage.Entry> list = rows();
        if (list.isEmpty()) return false;
        if (keyCode == 265 && selected > 0) {
            selected--;
            ensureVisible();
            screen.refreshWidgets();
            return true;
        }
        if (keyCode == 264 && selected < list.size() - 1) {
            selected = selected < 0 ? 0 : selected + 1;
            ensureVisible();
            screen.refreshWidgets();
            return true;
        }
        return false;
    }

    private void ensureVisible() {
        if (selected < scroll) scroll = selected;
        int visible = visibleRows();
        if (selected >= scroll + visible) scroll = selected - visible + 1;
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        if (listRect.length != 4) return false;
        if (mx < listRect[0] || mx >= listRect[2] || my < listRect[1] || my >= listRect[3]) return false;
        int maxScroll = Math.max(0, rows().size() - visibleRows());
        if (maxScroll <= 0) return false;
        scroll = Math.max(0, Math.min(maxScroll, scroll + (delta > 0 ? -1 : 1)));
        return true;
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        if (PmScrollbar.isDragging()) {
            int next = PmScrollbar.dragV(SCROLL_ID, my);
            if (next >= 0) scroll = next;
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        PmScrollbar.endDrag();
        return false;
    }

    private static String tr(String key) {
        return Component.translatable(key).getString();
    }
}
