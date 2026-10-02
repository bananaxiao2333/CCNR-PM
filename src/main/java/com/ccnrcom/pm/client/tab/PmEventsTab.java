/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.pm.client.tab;

import com.ccnrcom.pm.client.ClientMatchState;
import com.ccnrcom.pm.client.ui.PmScrollbar;
import com.ccnrcom.pm.client.ui.PmTab;
import com.ccnrcom.pm.client.ui.PmTheme;
import com.ccnrcom.pm.integration.MatchSnapshot;
import com.ccnrcom.pm.util.TimeText;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

/**
 * 「当局事件」页签：这一局里发生过什么（击杀、死亡、阶段推进、事件启停、结算……）。
 *
 * <h2>数据从哪来</h2>
 * CCNR-RP 的 {@code DataLink.recentEvents()}——与它自己的 {@code /rp data events} 命令是**同一份**内存环形缓冲，
 * 由服务端在 {@code integration.RpBridge} 里取出后随对局快照一起下发。
 *
 * <h2>为什么这个页签在数据服务掉线时照样有内容</h2>
 * 事件流是**本地**环形缓冲，可见性刻意不依赖与数据服务器的连通性（对方 docs/19 的设计）：
 * 「上报没上去」与「看得见发生了什么」是两件事，把它们绑在一起会让管理员在最需要看事件的
 * 故障时刻恰好什么都看不到。
 *
 * <h2>为什么最新的一行在最上面</h2>
 * 这是一个开着面板盯的活数据源。正序（旧→新）意味着每来一条新事件都要往下滚，
 * 而管理员最常问的问题是「刚才发生了什么」。因此倒序渲染：**打开即最新，不需要滚动**。
 *
 * <h2>为什么没有 widget</h2>
 * 与对局页签同理：快照每秒刷新，一旦持有 widget 就得每秒重建界面，
 * 而那会打断工单页签里正在输入的备注（见 {@code ClientMatchState} 的类注释）。
 */
public final class PmEventsTab implements PmTab {

    private static final int ROW_H = 11;
    private static final int HDR_H = 14;
    private static final int SCROLL_ID = 83; // 面板内唯一

    /** 事件种类列的固定宽度（{@code CHARACTER_KILL} 这类短标签够用，正文占其余宽度）。 */
    private static final int KIND_W = 110;

    private int x1;
    private int y1;
    private int x2;
    private int y2;

    private int scroll;

    @Override
    public String id() {
        return "events";
    }

    private List<MatchSnapshot.LogLine> lines() {
        return ClientMatchState.snapshot().map(MatchSnapshot::log).orElse(List.of());
    }

    /** 倒序视图索引 → 原始索引（最新的一条在最上面）。 */
    private List<MatchSnapshot.LogLine> newestFirst() {
        List<MatchSnapshot.LogLine> src = lines();
        List<MatchSnapshot.LogLine> out = new ArrayList<>(src.size());
        for (int i = src.size() - 1; i >= 0; i--) out.add(src.get(i));
        return out;
    }

    @Override
    public void rebuild(int x1, int y1, int x2, int y2) {
        this.x1 = x1;
        this.y1 = y1;
        this.x2 = x2;
        this.y2 = y2;
    }

    private int visibleRows() {
        return Math.max(1, (y2 - y1 - HDR_H) / ROW_H);
    }

    // ------------------------------------------------------------------
    // 绘制
    // ------------------------------------------------------------------

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY) {
        Font font = Minecraft.getInstance().font;
        PmTheme.listPanel(g, x1 - 2, y1 - 4, x2 + 2, y2 + 2);

        List<MatchSnapshot.LogLine> list = newestFirst();
        String header = PmTheme.section("EVENTS") + "  " + trf("ccnr_pm.events.count", list.size());
        g.drawString(font, header, x1 + 2, y1 + 2, PmTheme.TEXT_DIM, false);

        if (ClientMatchState.snapshot().isEmpty()) {
            g.drawString(font, tr("ccnr_pm.panel.loading"), x1 + 4, y1 + HDR_H + 4, PmTheme.TEXT_DISABLED, false);
            return;
        }
        if (!ClientMatchState.rpAvailable()) {
            g.drawString(font, tr("ccnr_pm.match.absent"), x1 + 4, y1 + HDR_H + 4, PmTheme.TEXT_DISABLED, false);
            return;
        }
        if (list.isEmpty()) {
            g.drawString(font, tr("ccnr_pm.events.empty"), x1 + 4, y1 + HDR_H + 4, PmTheme.TEXT_DISABLED, false);
            return;
        }
        PmTheme.listHeaderRule(g, x1, x2, y1 + HDR_H - 1);

        int visible = visibleRows();
        scroll = Math.max(0, Math.min(Math.max(0, list.size() - visible), scroll));

        int timeW = font.width("00:00:00");
        // 正文列：扣掉时间列、种类列与两处留白，再给滚动条让出 6px
        int textX = x1 + 4 + timeW + 6 + KIND_W + 6;
        int textW = Math.max(20, (x2 - 6) - textX);

        for (int i = 0; i < visible; i++) {
            int idx = scroll + i;
            if (idx >= list.size()) break;
            MatchSnapshot.LogLine line = list.get(idx);
            int ry1 = y1 + HDR_H + i * ROW_H;
            int ry2 = ry1 + ROW_H;
            boolean hovered = mouseX >= x1 && mouseX < x2 && mouseY >= ry1 && mouseY < ry2;
            PmTheme.listRow(g, x1, ry1, x2, ry2, idx, hovered);

            int ty = ry1 + 1;
            // 时间：缺时间戳时 TimeText 给等宽占位，列表不会因此错位
            g.drawString(font, TimeText.clock(line.atMs()), x1 + 4, ty, PmTheme.TEXT_DIM, false);
            g.drawString(
                    font,
                    PmTheme.clip(font, line.shortKind(), KIND_W),
                    x1 + 4 + timeW + 6,
                    ty,
                    PmTheme.TEXT_SECONDARY,
                    false);
            g.drawString(font, PmTheme.clip(font, line.text(), textW), textX, ty, PmTheme.TEXT_PRIMARY, false);
        }
        PmScrollbar.draw(g, x2 - 6, y1 + HDR_H, y2, list.size(), visible, scroll);
    }

    // ------------------------------------------------------------------
    // 输入
    // ------------------------------------------------------------------

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (button != 0) return false;
        // 本页签没有可点的行，但仍然要处理滚动条（拖拽 / 翻页）
        int next = PmScrollbar.clickV(
                mx, my, x2 - 6, x2, y1 + HDR_H, y2, lines().size(), visibleRows(), scroll, SCROLL_ID);
        if (next >= 0) {
            scroll = next;
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        if (mx < x1 || mx >= x2 || my < y1 || my >= y2) return false;
        int maxScroll = Math.max(0, lines().size() - visibleRows());
        if (maxScroll <= 0) return false;
        scroll = Math.max(0, Math.min(maxScroll, scroll + (delta > 0 ? -1 : 1)));
        return true;
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        if (!PmScrollbar.isDragging()) return false;
        int next = PmScrollbar.dragV(SCROLL_ID, my);
        if (next >= 0) {
            scroll = next;
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        PmScrollbar.endDrag();
        return false;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode != 265 && keyCode != 264) return false;
        int maxScroll = Math.max(0, lines().size() - visibleRows());
        scroll = Math.max(0, Math.min(maxScroll, scroll + (keyCode == 265 ? -1 : 1)));
        return true;
    }

    private static String tr(String key) {
        return Component.translatable(key).getString();
    }

    private static String trf(String key, Object... args) {
        return Component.translatable(key, args).getString();
    }
}
