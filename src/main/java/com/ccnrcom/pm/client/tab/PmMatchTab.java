/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.pm.client.tab;

import com.ccnrcom.pm.client.ClientMatchState;
import com.ccnrcom.pm.client.ui.PmButton;
import com.ccnrcom.pm.client.ui.PmScrollbar;
import com.ccnrcom.pm.client.ui.PmTab;
import com.ccnrcom.pm.client.ui.PmTheme;
import com.ccnrcom.pm.integration.MatchSnapshot;
import com.ccnrcom.pm.network.PmChannel;
import com.ccnrcom.pm.network.PmPackets;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

/**
 * 「对局管理」页签：看当局状态、切幕、触发/结束事件、收束与重开。
 *
 * <h2>数据从哪来</h2>
 * 全部来自 {@link ClientMatchState} 里的那一份 {@link MatchSnapshot}——它由服务端反射读
 * CCNR-RP 得到（见 {@code integration.RpBridge}）。**本页签不含任何对局规则**：
 * 「第几幕 / 还剩多久 / 哪些事件能触发」都是对方的答案，PM 只负责画出来与转发指令。
 *
 * <h2>为什么没有 widget</h2>
 * 页签不持有任何 {@code EditBox}/{@code Button}：按钮是手绘 + 命中测试（与工单页签同一套
 * {@code PmButton}）。这让**每秒刷新**的对局快照不需要重建界面——一旦有 widget，
 * 每次刷新都要 {@code clearWidgets()} 再重建，而那是全局的，会打断工单页签里正在输入的备注
 * （见 {@code ClientMatchState} 的类注释）。
 *
 * <h2>为什么状态区固定预留四行</h2>
 * 第四行是「上一条动作的失败原因」。若它出现时才撑高，下方两个列表会在管理员刚点完按钮的瞬间
 * **整体跳一下**——鼠标还在原处，很容易误点到刚移到指针下的另一行。因此这一行始终占位，空着不画字。
 *
 * <h2>服务端权威</h2>
 * 按钮的可用性只是体验（例如「结束」只对正在运行的事件可点），真正的判定在 CCNR-RP 自己那里；
 * 点任意按钮都只发一条指令，本地**从不自己改状态**，一切以服务端回推的新快照为准。
 */
public final class PmMatchTab implements PmTab {

    private static final int ROW_H = 16;
    private static final int HDR_H = 14;
    private static final int BTN_H = 18;

    /** 状态区高度：4 行 × 11px + 上下留白（第四行固定预留，见类注释）。 */
    private static final int STATUS_H = 46;

    /** 行间细分隔：列表底边到按钮行、按钮行之间的间距。 */
    private static final int GAP = 4;

    private static final int SCROLL_PHASE = 81; // 面板内唯一
    private static final int SCROLL_EVENT = 82;

    private int x1;
    private int y1;
    private int x2;
    private int y2;

    private int[] phaseRect = new int[0];
    private int[] eventRect = new int[0];
    /** 左列两个按钮：[下一幕][切到选中幕]。 */
    private int[] phaseBtnRects = new int[0];
    /** 右列一个按钮：[触发 / 结束]。 */
    private int[] eventBtnRect = new int[0];
    /** 底部三个全局按钮：[收束对局][重开一局][播放结束动画]。 */
    private int[] globalBtnRects = new int[12];

    private int phaseScroll;
    private int eventScroll;
    private int selPhase = -1;
    private int selEvent = -1;

    @Override
    public String id() {
        return "match";
    }

    // ------------------------------------------------------------------
    // 数据
    // ------------------------------------------------------------------

    private Optional<MatchSnapshot> snap() {
        return ClientMatchState.snapshot();
    }

    private List<MatchSnapshot.Phase> phases() {
        return snap().map(MatchSnapshot::phases).orElse(List.of());
    }

    private List<MatchSnapshot.Event> events() {
        return snap().map(MatchSnapshot::events).orElse(List.of());
    }

    private Optional<MatchSnapshot.Phase> selectedPhase() {
        List<MatchSnapshot.Phase> list = phases();
        if (selPhase < 0 || selPhase >= list.size()) return Optional.empty();
        return Optional.of(list.get(selPhase));
    }

    private Optional<MatchSnapshot.Event> selectedEvent() {
        List<MatchSnapshot.Event> list = events();
        if (selEvent < 0 || selEvent >= list.size()) return Optional.empty();
        return Optional.of(list.get(selEvent));
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

        // 底部两行按钮（全局动作一行、两列的列内动作各一个），列表占其余空间
        int colRowTop = y2 - BTN_H * 2 - GAP;
        int colRowBottom = y2 - BTN_H - GAP;
        int listTop = y1 + STATUS_H;
        int listBottom = Math.max(listTop + HDR_H + ROW_H, colRowTop - GAP);

        int listW = Math.max(140, (x2 - x1 - 8) / 2);
        int splitX = Math.min(x1 + listW, x2 - 80);
        phaseRect = new int[] {x1, listTop, splitX, listBottom};
        eventRect = new int[] {splitX + 8, listTop, x2, listBottom};

        int halfBtn = Math.max(50, (phaseRect[2] - phaseRect[0] - GAP) / 2);
        phaseBtnRects = new int[] {
            phaseRect[0],
            colRowTop,
            phaseRect[0] + halfBtn,
            colRowBottom,
            phaseRect[0] + halfBtn + GAP,
            colRowTop,
            phaseRect[2],
            colRowBottom
        };
        eventBtnRect = new int[] {eventRect[0], colRowTop, eventRect[2], colRowBottom};

        int gw = Math.max(60, (x2 - x1 - GAP * 2) / 3);
        int gRowTop = y2 - BTN_H;
        for (int i = 0; i < 3; i++) {
            int bx = x1 + i * (gw + GAP);
            globalBtnRects[i * 4] = bx;
            globalBtnRects[i * 4 + 1] = gRowTop;
            globalBtnRects[i * 4 + 2] = Math.min(bx + gw, x2);
            globalBtnRects[i * 4 + 3] = y2;
        }

        // 选中项可能因刷新（对方重载了配置）而消失：**先夹紧索引**，否则按钮会指向不存在的目标
        if (selPhase >= phases().size()) selPhase = phases().isEmpty() ? -1 : phases().size() - 1;
        if (selEvent >= events().size()) selEvent = events().isEmpty() ? -1 : events().size() - 1;
    }

    private int visibleRows(int[] rect) {
        if (rect.length < 4) return 1;
        return Math.max(1, (rect[3] - rect[1] - HDR_H) / ROW_H);
    }

    // ------------------------------------------------------------------
    // 绘制
    // ------------------------------------------------------------------

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY) {
        renderStatus(g);
        renderPhases(g, mouseX, mouseY);
        renderEvents(g, mouseX, mouseY);
        renderButtons(g, mouseX, mouseY);
    }

    private void renderStatus(GuiGraphics g) {
        Font font = Minecraft.getInstance().font;
        Optional<MatchSnapshot> opt = snap();
        int w = x2 - x1;

        if (opt.isEmpty()) {
            g.drawString(font, tr("ccnr_pm.panel.loading"), x1, y1 + 2, PmTheme.TEXT_DISABLED, false);
            return;
        }
        MatchSnapshot s = opt.get();
        if (!s.available()) {
            g.drawString(font, tr("ccnr_pm.match.absent"), x1, y1 + 2, PmTheme.TEXT_DISABLED, false);
            return;
        }

        // 第 1 行：对局是否在跑 + 模式（空窗期也要能一眼看出，否则管理员会去点切幕按钮）
        String state = tr(s.running() ? "ccnr_pm.match.running" : "ccnr_pm.match.idle");
        String mode = s.modeName().isBlank() ? tr("ccnr_pm.match.mode_none") : s.modeName();
        drawPair(g, 2, "ccnr_pm.match.field.state", state, PmTheme.TEXT_PRIMARY, "ccnr_pm.match.field.mode", mode, w);

        // 第 2 行：第 n/m 幕 + 倒计时（条件驱动幕没有倒计时，改显「条件驱动」）
        String phase = s.phaseName().isBlank() ? tr("ccnr_pm.match.phase_none") : phaseText(s);
        drawPair(
                g,
                13,
                "ccnr_pm.match.field.phase",
                phase,
                PmTheme.TEXT_PRIMARY,
                "ccnr_pm.match.field.timer",
                timerText(s),
                w);

        // 第 3 行：进行中的事件
        label(g, 24, "ccnr_pm.match.field.active");
        int lx = x1 + font.width(tr("ccnr_pm.match.field.active"));
        List<String> active = s.activeEventNames();
        String activeText = active.isEmpty() ? tr("ccnr_pm.gui.none") : String.join("、", active);
        g.drawString(
                font,
                PmTheme.clip(font, activeText, Math.max(10, (x1 + w) - lx)),
                lx,
                y1 + 24,
                PmTheme.TEXT_BRIGHT,
                false);

        // 第 4 行（固定预留）：上一次动作的失败原因
        String err = ClientMatchState.errorKey();
        if (!err.isBlank()) {
            g.drawString(font, PmTheme.clip(font, tr(err), w), x1, y1 + 35, PmTheme.RED_LINE, false);
        }
    }

    /** {@code 第 n/m 幕 名称}；对方没给总数（旧配置）时只显示幕名。 */
    private static String phaseText(MatchSnapshot s) {
        if (s.phaseTotal() <= 0 || s.phaseIndex() < 0) return s.phaseName();
        return (s.phaseIndex() + 1) + "/" + s.phaseTotal() + " " + s.phaseName();
    }

    /** 「标签：值」+ 中缝「标签：值」两段式状态行（第二段固定在面板中线，两行对齐）。 */
    private void drawPair(
            GuiGraphics g, int dy, String labelA, String valueA, int inkA, String labelB, String valueB, int w) {
        Font font = Minecraft.getInstance().font;
        label(g, dy, labelA);
        int ax = x1 + font.width(tr(labelA));
        int aMax = Math.max(10, x1 + w / 2 - 6 - ax);
        g.drawString(font, PmTheme.clip(font, valueA, aMax), ax, y1 + dy, inkA, false);

        int bx = x1 + w / 2;
        g.drawString(font, tr(labelB), bx, y1 + dy, PmTheme.TEXT_DIM, false);
        int vx = bx + font.width(tr(labelB));
        g.drawString(
                font, PmTheme.clip(font, valueB, Math.max(10, (x1 + w) - vx)), vx, y1 + dy, PmTheme.TEXT_BRIGHT, false);
    }

    private void label(GuiGraphics g, int dy, String key) {
        g.drawString(Minecraft.getInstance().font, tr(key), x1, y1 + dy, PmTheme.TEXT_DIM, false);
    }

    /** 倒计时文本：优先行为序列计时，其次幕剩余；都没有则给一句说明而不是 {@code 00:00}。 */
    private String timerText(MatchSnapshot s) {
        long now = System.currentTimeMillis();
        long seq = s.seqRemainMs(now);
        if (seq >= 0) return trf("ccnr_pm.match.timer.seq", mmss(seq));
        long remain = s.phaseRemainMs(now);
        if (remain >= 0) return mmss(remain);
        return tr(s.phaseConditionDriven() ? "ccnr_pm.match.timer.condition" : "ccnr_pm.match.timer.none");
    }

    private static String mmss(long ms) {
        long total = Math.max(0L, ms) / 1000L;
        // Locale.ROOT：数字格式不该随系统区域变化（土耳其等区域会给出不同的数字形状）
        return String.format(Locale.ROOT, "%02d:%02d", total / 60L, total % 60L);
    }

    private void renderPhases(GuiGraphics g, int mouseX, int mouseY) {
        if (phaseRect.length < 4) return;
        Font font = Minecraft.getInstance().font;
        int lx1 = phaseRect[0];
        int ly1 = phaseRect[1];
        int lx2 = phaseRect[2];
        int ly2 = phaseRect[3];

        PmTheme.listPanel(g, lx1 - 2, ly1 - 4, lx2 + 2, ly2 + 2);
        g.drawString(font, PmTheme.section("PHASES"), lx1 + 2, ly1 + 2, PmTheme.TEXT_DIM, false);

        List<MatchSnapshot.Phase> list = phases();
        if (list.isEmpty()) {
            g.drawString(font, tr("ccnr_pm.match.phase_empty"), lx1 + 4, ly1 + HDR_H + 4, PmTheme.TEXT_DISABLED, false);
            return;
        }
        PmTheme.listHeaderRule(g, lx1, lx2, ly1 + HDR_H - 1);

        String current = snap().map(MatchSnapshot::phaseId).orElse("");
        int visible = visibleRows(phaseRect);
        phaseScroll = clampScroll(phaseScroll, list.size(), visible);

        int textW = (lx2 - 6) - (lx1 + 4);
        for (int i = 0; i < visible; i++) {
            int idx = phaseScroll + i;
            if (idx >= list.size()) break;
            MatchSnapshot.Phase p = list.get(idx);
            int ry1 = ly1 + HDR_H + i * ROW_H;
            int ry2 = ry1 + ROW_H;
            boolean isSel = idx == selPhase;
            boolean hovered = mouseX >= lx1 && mouseX < lx2 && mouseY >= ry1 && mouseY < ry2;
            if (isSel) {
                PmTheme.selectedBar(g, lx1, ry1, lx2, ry2, 0f);
            } else {
                PmTheme.listRow(g, lx1, ry1, lx2, ry2, idx, hovered);
            }
            // 亮底（选中行）必须深字；非选中行里「当前幕」用亮字区别于其它幕
            boolean isCurrent = p.id().equals(current);
            int ink = isSel ? PmTheme.ACCENT_TEXT : (isCurrent ? PmTheme.TEXT_PRIMARY : PmTheme.TEXT_SECONDARY);
            // 序号按**对方给的顺序**编号（列表本来就是对方的顺序，不另外排序）
            String text = (idx + 1) + "  " + p.name() + (isCurrent ? "  " + tr("ccnr_pm.match.phase_current") : "");
            g.drawString(font, PmTheme.clip(font, text, textW), lx1 + 4, ry1 + 4, ink, false);
        }
        PmScrollbar.draw(g, lx2 - 6, ly1 + HDR_H, ly2, list.size(), visible, phaseScroll);
    }

    private void renderEvents(GuiGraphics g, int mouseX, int mouseY) {
        if (eventRect.length < 4) return;
        Font font = Minecraft.getInstance().font;
        int lx1 = eventRect[0];
        int ly1 = eventRect[1];
        int lx2 = eventRect[2];
        int ly2 = eventRect[3];

        PmTheme.listPanel(g, lx1 - 2, ly1 - 4, lx2 + 2, ly2 + 2);
        g.drawString(font, PmTheme.section("EVENTS"), lx1 + 2, ly1 + 2, PmTheme.TEXT_DIM, false);

        List<MatchSnapshot.Event> list = events();
        if (list.isEmpty()) {
            g.drawString(font, tr("ccnr_pm.match.event_empty"), lx1 + 4, ly1 + HDR_H + 4, PmTheme.TEXT_DISABLED, false);
            return;
        }
        PmTheme.listHeaderRule(g, lx1, lx2, ly1 + HDR_H - 1);

        int visible = visibleRows(eventRect);
        eventScroll = clampScroll(eventScroll, list.size(), visible);

        int textW = (lx2 - 6) - (lx1 + 4);
        for (int i = 0; i < visible; i++) {
            int idx = eventScroll + i;
            if (idx >= list.size()) break;
            MatchSnapshot.Event e = list.get(idx);
            int ry1 = ly1 + HDR_H + i * ROW_H;
            int ry2 = ry1 + ROW_H;
            boolean isSel = idx == selEvent;
            boolean hovered = mouseX >= lx1 && mouseX < lx2 && mouseY >= ry1 && mouseY < ry2;
            if (isSel) {
                PmTheme.selectedBar(g, lx1, ry1, lx2, ry2, 0f);
            } else {
                PmTheme.listRow(g, lx1, ry1, lx2, ry2, idx, hovered);
            }

            String state = stateName(e.state());
            int stateW = font.width(state);
            String name = PmTheme.clip(font, e.name(), Math.max(10, textW - stateW - 6));
            g.drawString(font, name, lx1 + 4, ry1 + 4, isSel ? PmTheme.ACCENT_TEXT : PmTheme.TEXT_PRIMARY, false);
            if (isSel) {
                // 亮底必须深字，因此选中行不给状态上色——那条硬性约束优先于配色
                g.drawString(font, state, lx1 + 4 + font.width(name) + 6, ry1 + 4, PmTheme.ACCENT_TEXT, false);
            } else {
                g.drawString(font, state, lx2 - 6 - stateW, ry1 + 4, PmTheme.eventStateColor(e.state()), false);
            }
        }
        PmScrollbar.draw(g, lx2 - 6, ly1 + HDR_H, ly2, list.size(), visible, eventScroll);
    }

    /** 事件状态的中文名；未知状态直接显示原文（对方加了新状态时不会显示成空白）。 */
    private static String stateName(String state) {
        String key =
                switch (state == null ? "" : state) {
                    case "RUNNING" -> "ccnr_pm.match.state.running";
                    case "SETTLED" -> "ccnr_pm.match.state.settled";
                    case "SCHEDULED" -> "ccnr_pm.match.state.scheduled";
                    default -> "";
                };
        return key.isEmpty() ? (state == null ? "" : state) : tr(key);
    }

    private void renderButtons(GuiGraphics g, int mouseX, int mouseY) {
        Font font = Minecraft.getInstance().font;
        boolean rp = ClientMatchState.rpAvailable();
        Optional<MatchSnapshot.Event> sel = selectedEvent();
        boolean eventOn = sel.map(MatchSnapshot.Event::triggerable).orElse(false);
        boolean eventOff = sel.map(MatchSnapshot.Event::endable).orElse(false);
        boolean running = snap().map(MatchSnapshot::running).orElse(false);

        drawBtn(
                g,
                font,
                phaseBtnRects,
                0,
                tr("ccnr_pm.match.btn.next_phase"),
                PmButton.Variant.SECONDARY,
                rp && !phases().isEmpty(),
                mouseX,
                mouseY);
        drawBtn(
                g,
                font,
                phaseBtnRects,
                4,
                tr("ccnr_pm.match.btn.set_phase"),
                PmButton.Variant.PRIMARY,
                rp && selectedPhase().isPresent(),
                mouseX,
                mouseY);

        // 一个按钮承担「触发 / 结束」两件事：目标事件的状态决定它是哪一件。
        // 拆成两个按钮只会让其中一个在大多数时候是灰的（事件要么待触发、要么在运行）。
        boolean endMode = eventOff;
        drawBtn(
                g,
                font,
                eventBtnRect,
                0,
                tr(endMode ? "ccnr_pm.match.btn.event_off" : "ccnr_pm.match.btn.event_on"),
                endMode ? PmButton.Variant.DANGER : PmButton.Variant.PRIMARY,
                rp && (eventOn || eventOff),
                mouseX,
                mouseY);

        drawBtn(
                g,
                font,
                globalBtnRects,
                0,
                tr("ccnr_pm.match.btn.end_match"),
                PmButton.Variant.DANGER,
                rp && running,
                mouseX,
                mouseY);
        drawBtn(
                g,
                font,
                globalBtnRects,
                4,
                tr("ccnr_pm.match.btn.reset_match"),
                PmButton.Variant.DANGER,
                rp,
                mouseX,
                mouseY);
        drawBtn(
                g,
                font,
                globalBtnRects,
                8,
                tr("ccnr_pm.match.btn.game_over"),
                PmButton.Variant.SECONDARY,
                rp,
                mouseX,
                mouseY);
    }

    private static void drawBtn(
            GuiGraphics g,
            Font font,
            int[] rects,
            int off,
            String label,
            PmButton.Variant variant,
            boolean enabled,
            int mouseX,
            int mouseY) {
        if (rects.length < off + 4) return;
        int a = rects[off];
        int b = rects[off + 1];
        int c = rects[off + 2];
        int d = rects[off + 3];
        boolean hovered = enabled && PmButton.hit(mouseX, mouseY, a, b, c, d);
        PmButton.draw(g, font, a, b, c, d, label, variant, hovered, enabled);
    }

    // ------------------------------------------------------------------
    // 输入
    // ------------------------------------------------------------------

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (button != 0) return false;

        // 滚动条优先于按钮与列表行：它画在列表右缘，与两者都可能相邻
        int next = scrollClick(mx, my, phaseRect, phases().size(), phaseScroll, SCROLL_PHASE);
        if (next >= 0) {
            phaseScroll = next;
            return true;
        }
        next = scrollClick(mx, my, eventRect, events().size(), eventScroll, SCROLL_EVENT);
        if (next >= 0) {
            eventScroll = next;
            return true;
        }

        if (hitRow(phaseRect, mx, my)) {
            int idx = phaseScroll + (int) (my - (phaseRect[1] + HDR_H)) / ROW_H;
            if (idx >= 0 && idx < phases().size()) selPhase = idx;
            return true;
        }
        if (hitRow(eventRect, mx, my)) {
            int idx = eventScroll + (int) (my - (eventRect[1] + HDR_H)) / ROW_H;
            if (idx >= 0 && idx < events().size()) selEvent = idx;
            return true;
        }
        return clickButtons(mx, my);
    }

    /** 滚动条的点击（滑块→开始拖拽，轨道→翻页）；返回新的偏移量，未命中返回 -1。 */
    private static int scrollClick(double mx, double my, int[] rect, int total, int offset, int id) {
        if (rect.length < 4) return -1;
        return PmScrollbar.clickV(
                mx, my, rect[2] - 6, rect[2], rect[1] + HDR_H, rect[3], total, visibleRowCount(rect), offset, id);
    }

    private boolean clickButtons(double mx, double my) {
        if (!ClientMatchState.rpAvailable()) return false;

        if (phaseBtnRects.length == 8
                && PmButton.hit(mx, my, phaseBtnRects[0], phaseBtnRects[1], phaseBtnRects[2], phaseBtnRects[3])
                && !phases().isEmpty()) {
            // 空 arg = 下一幕（CCNR-RP 自己的 switchPhase("") 语义，PM 只透传）
            send(PmPackets.ACT_MATCH_PHASE, "");
            return true;
        }
        if (phaseBtnRects.length == 8
                && PmButton.hit(mx, my, phaseBtnRects[4], phaseBtnRects[5], phaseBtnRects[6], phaseBtnRects[7])) {
            selectedPhase().ifPresent(p -> send(PmPackets.ACT_MATCH_PHASE, p.id()));
            return true;
        }
        if (eventBtnRect.length == 4
                && PmButton.hit(mx, my, eventBtnRect[0], eventBtnRect[1], eventBtnRect[2], eventBtnRect[3])) {
            selectedEvent().ifPresent(e -> {
                if (e.endable()) send(PmPackets.ACT_MATCH_EVENT_OFF, e.id());
                else if (e.triggerable()) send(PmPackets.ACT_MATCH_EVENT_ON, e.id());
            });
            return true;
        }
        if (globalBtnRects.length == 12) {
            if (PmButton.hit(mx, my, globalBtnRects[0], globalBtnRects[1], globalBtnRects[2], globalBtnRects[3])
                    && snap().map(MatchSnapshot::running).orElse(false)) {
                send(PmPackets.ACT_MATCH_END, "");
                return true;
            }
            if (PmButton.hit(mx, my, globalBtnRects[4], globalBtnRects[5], globalBtnRects[6], globalBtnRects[7])) {
                send(PmPackets.ACT_MATCH_RESET, "");
                return true;
            }
            if (PmButton.hit(mx, my, globalBtnRects[8], globalBtnRects[9], globalBtnRects[10], globalBtnRects[11])) {
                send(PmPackets.ACT_MATCH_GAME_OVER, "");
                return true;
            }
        }
        return false;
    }

    /** 列表内容区命中（排除滚动条那 6px，否则点滑块会同时选中一行）。 */
    private static boolean hitRow(int[] rect, double mx, double my) {
        return rect.length == 4 && mx >= rect[0] && mx < rect[2] - 6 && my >= rect[1] + HDR_H && my < rect[3];
    }

    /** 发指令并清掉上一条错误；**不改本地状态**，等服务端回推新快照。 */
    private void send(String action, String arg) {
        ClientMatchState.clearError();
        PmChannel.sendToServer(new PmPackets.MatchActionC2S(action, arg));
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode != 265 && keyCode != 264) return false;
        int step = keyCode == 265 ? -1 : 1;
        List<MatchSnapshot.Phase> ps = phases();
        List<MatchSnapshot.Event> es = events();
        // 上下键：幕表被选中（或事件表为空）时走幕表，否则走事件表——
        // 单一焦点在两表间切换比给两表各配一组快捷键更不容易误操作
        if (!ps.isEmpty() && (selPhase >= 0 || es.isEmpty())) {
            selPhase = Math.max(0, Math.min(ps.size() - 1, (selPhase < 0 ? 0 : selPhase) + step));
            phaseScroll = ensureVisible(phaseScroll, selPhase, ps.size(), visibleRows(phaseRect));
        } else if (!es.isEmpty()) {
            selEvent = Math.max(0, Math.min(es.size() - 1, (selEvent < 0 ? 0 : selEvent) + step));
            eventScroll = ensureVisible(eventScroll, selEvent, es.size(), visibleRows(eventRect));
        } else {
            return false;
        }
        return true;
    }

    private static int ensureVisible(int scroll, int sel, int total, int visible) {
        if (sel < scroll) scroll = sel;
        if (sel >= scroll + visible) scroll = sel - visible + 1;
        return clampScroll(scroll, total, visible);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        int step = delta > 0 ? -1 : 1;
        if (inRect(phaseRect, mx, my)) {
            phaseScroll = clampScroll(phaseScroll + step, phases().size(), visibleRows(phaseRect));
            return true;
        }
        if (inRect(eventRect, mx, my)) {
            eventScroll = clampScroll(eventScroll + step, events().size(), visibleRows(eventRect));
            return true;
        }
        return false;
    }

    private static boolean inRect(int[] rect, double mx, double my) {
        return rect.length == 4 && mx >= rect[0] && mx < rect[2] && my >= rect[1] && my < rect[3];
    }

    private static int clampScroll(int value, int total, int visible) {
        return Math.max(0, Math.min(Math.max(0, total - visible), value));
    }

    private static int visibleRowCount(int[] rect) {
        if (rect.length < 4) return 1;
        return Math.max(1, (rect[3] - rect[1] - HDR_H) / ROW_H);
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        if (!PmScrollbar.isDragging()) return false;
        int next = PmScrollbar.dragV(SCROLL_PHASE, my);
        if (next >= 0) {
            phaseScroll = next;
            return true;
        }
        next = PmScrollbar.dragV(SCROLL_EVENT, my);
        if (next >= 0) {
            eventScroll = next;
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

    /** 带参数的语言键（倒计时等）。 */
    private static String trf(String key, Object... args) {
        return Component.translatable(key, args).getString();
    }
}
