/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.pm.client.ui;

import com.ccnrcom.pm.client.ui.PmChipModel.Candidate;
import com.ccnrcom.pm.ticket.TicketService;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;

/**
 * 「关联玩家」胶囊输入框（用户要求：选中的玩家像 QQ 一样变成一个整体）。
 *
 * <h2>它长什么样</h2>
 * <pre>
 * ┌────────────────────────────────────────────────┐
 * │ ( Alice ×) ( Bob ×) |                        │  ← 胶囊 + 当前输入（带光标）
 * │                                                │  ← 第二行（胶囊放不下时换行）
 * └────────────────────────────────────────────────┘
 *        ↓ 输入前缀时下方弹候选
 *   ┌──────────────┐
 *   │ Alice        │
 *   │ Alfred       │
 *   └──────────────┘
 * </pre>
 *
 * <h2>两条硬性规则</h2>
 * <ol>
 *   <li><b>只能从在线名单里选</b>（用户明确要求）：输入框里的文字只是过滤条件，
 *       只有按回车/点击确认成胶囊的玩家才会进入 {@link #value()}。</li>
 *   <li><b>绘制与命中判定共用同一处坐标</b>：胶囊矩形只由 {@link #chipRects()} 计算一次，
 *       渲染、悬停、点 × 全用它——两处各算一份正是「看到的 × 和点到的 × 不是一个」的成因。</li>
 * </ol>
 *
 * <p>文本模型（胶囊、过滤、退格手感、提交值）在纯类 {@link PmChipModel} 里，本类只做渲染与输入。
 */
public class PmChipInput extends AbstractWidget {

    /** 单个胶囊的高度。 */
    private static final int CHIP_H = 14;

    /** 胶囊内左右留白。 */
    private static final int CHIP_PAD = 4;

    /** 胶囊之间的间隔。 */
    private static final int CHIP_GAP = 3;

    /** × 与名字之间的间隔。 */
    private static final int X_GAP = 3;

    /** 每行内胶囊的纵向内缩（行高 18 里放 14 的胶囊）。 */
    private static final int ROW_INSET = 2;

    /** 当前输入至少要有这么宽才留在同一行，否则换到下一行。 */
    private static final int MIN_INPUT_W = 24;

    /** 候选弹层最多显示几行。 */
    private static final int POPUP_ROWS = 6;

    /** 候选弹层行高。 */
    private static final int POPUP_ROW_H = 12;

    /**
     * 候选弹层滚动条 id。
     *
     * <p>必须与同屏其它滚动条不同：{@code TicketScreen} 用 1（类别弹层）与 2（详细描述框），
     * 这里用 3。id 撞车的后果是拖一个滚动条动的是另一个列表。
     */
    private static final int SCROLL_ID = 3;

    private static final int KEY_ESC = 256;
    private static final int KEY_ENTER = 257;
    private static final int KEY_TAB = 258;
    private static final int KEY_BACKSPACE = 259;
    private static final int KEY_DELETE = 261;
    private static final int KEY_RIGHT = 262;
    private static final int KEY_LEFT = 263;
    private static final int KEY_DOWN = 264;
    private static final int KEY_UP = 265;
    private static final int KEY_HOME = 268;
    private static final int KEY_END = 269;
    private static final int KEY_V = 86;

    private final PmChipModel model;
    private final List<Candidate> roster;
    private final String selfName;

    /** 行数（胶囊换行用）。 */
    private final int rows;

    private boolean editable = true;

    /** Esc 关掉候选弹层用（不清空玩家已输入的文字；下次打字自动恢复候选）。 */
    private boolean popupSuppressed;

    public PmChipInput(
            Font font, int x, int y, int width, int rows, PmChipModel model, List<TicketService.OnlinePlayer> roster) {
        super(x, y, width, Math.max(1, rows) * 18, Component.empty());
        this.rows = Math.max(1, rows);
        this.model = model;
        List<Candidate> list = new ArrayList<>();
        if (roster != null) {
            for (TicketService.OnlinePlayer p : roster) {
                if (p == null || p.name() == null || p.name().isBlank()) continue;
                list.add(new Candidate(p.uuid(), p.name()));
            }
        }
        this.roster = List.copyOf(list);
        Minecraft mc = Minecraft.getInstance();
        this.selfName = mc.player == null ? "" : mc.player.getGameProfile().getName();
    }

    public PmChipModel model() {
        return model;
    }

    /** 提交用的字段值（只有胶囊）。 */
    public String value() {
        return model.value();
    }

    public void setEditable(boolean editable) {
        this.editable = editable;
    }

    /** 候选列表（按当前输入前缀；与焦点无关，界面靠它显示「没有匹配的在线玩家」）。 */
    public List<Candidate> candidates() {
        if (!editable || popupSuppressed) return List.of();
        return model.candidates(roster, selfName);
    }

    /**
     * 是否有候选弹层（供界面判断「要点到弹层还是点到下层」）。
     *
     * <p>**必须要求获得焦点**：弹层是「我正在这个框里打字」的产物。焦点走了它还挂着，
     * 就会出现「屏幕上下着一片候选、点哪儿都被弹层吞掉」——而且哪一处点击都点不动。
     */
    public boolean hasPopup() {
        return isFocused() && !candidates().isEmpty();
    }

    // ------------------------------------------------------------------
    // 布局（渲染与命中共用）
    // ------------------------------------------------------------------

    /**
     * 每个胶囊的矩形与它在模型里的下标：{@code {x1,y1,x2,y2,index}}。
     *
     * <p>换行规则：一行放不下就换到下一行；行数用完则**不再画**（宁可少显示也不压出框外）。
     */
    private List<int[]> chipRects() {
        List<int[]> out = new ArrayList<>();
        List<Candidate> chips = model.chips();
        int x = getX() + CHIP_PAD;
        int row = 0;
        for (int i = 0; i < chips.size(); i++) {
            int w = chipWidth(chips.get(i).name());
            if (x + w > getX() + width - CHIP_PAD && x > getX() + CHIP_PAD) {
                row++;
                x = getX() + CHIP_PAD;
            }
            if (row >= rows) break;
            int y = getY() + row * 18 + ROW_INSET;
            out.add(new int[] {x, y, x + w, y + CHIP_H, i});
            x += w + CHIP_GAP;
        }
        return out;
    }

    private int chipWidth(String name) {
        Font f = Minecraft.getInstance().font;
        return CHIP_PAD + f.width(name) + X_GAP + f.width("×") + CHIP_PAD;
    }

    /** × 的命中矩形（在胶囊右侧）。 */
    private int[] closeRect(int[] chip) {
        Font f = Minecraft.getInstance().font;
        int w = f.width("×");
        int x2 = chip[2] - CHIP_PAD;
        return new int[] {x2 - w, chip[1], x2, chip[3]};
    }

    /** 当前输入文字的起点（最后一个胶囊之后；换行时落在下一行）。 */
    private int[] inputOrigin() {
        List<int[]> rects = chipRects();
        if (rects.isEmpty()) {
            return new int[] {getX() + CHIP_PAD, getY() + ROW_INSET + 3};
        }
        int[] last = rects.get(rects.size() - 1);
        int nextX = last[2] + CHIP_GAP;
        int row = (last[1] - getY() - ROW_INSET) / 18;
        if (nextX + MIN_INPUT_W > getX() + width - CHIP_PAD) {
            row++;
            if (row >= rows) {
                // 行数用满：挤在最后一行末尾（文字会被裁剪，但不会画到框外）
                return new int[] {nextX, last[1] + 3};
            }
            nextX = getX() + CHIP_PAD;
        }
        return new int[] {nextX, getY() + row * 18 + ROW_INSET + 3};
    }

    // ------------------------------------------------------------------
    // 渲染
    // ------------------------------------------------------------------

    @Override
    protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        Font f = Minecraft.getInstance().font;
        PmTheme.controlBox(g, getX(), getY(), getX() + width, getY() + height, isFocused());

        // 胶囊
        for (int[] r : chipRects()) {
            Candidate chip = model.chips().get(r[4]);
            boolean overChip = mouseX >= r[0] && mouseX < r[2] && mouseY >= r[1] && mouseY < r[3];
            int[] xRect = closeRect(r);
            boolean overX =
                    editable && mouseX >= xRect[0] && mouseX < xRect[2] && mouseY >= xRect[1] && mouseY < xRect[3];

            g.fill(r[0], r[1], r[2], r[3], overChip ? PmTheme.SURFACE_CONTROL_HOVER : PmTheme.SURFACE_CONTROL);
            PmTheme.outlined(g, r[0], r[1], r[2], r[3], overChip ? PmTheme.PANEL_BORDER_BRIGHT : PmTheme.PANEL_BORDER);
            g.drawString(f, chip.name(), r[0] + CHIP_PAD, r[1] + 3, PmTheme.TEXT_PRIMARY, false);
            int xw = f.width("×");
            g.drawString(f, "×", xRect[0], r[1] + 3, overX ? PmTheme.RED_LINE : PmTheme.TEXT_DIM, false);
            if (overX) {
                // 悬停时把 × 描一圈，明确「点这里删掉这个胶囊」
                PmTheme.outlined(g, xRect[0] - 1, xRect[1] + 1, xRect[0] + xw + 1, xRect[3] - 1, PmTheme.RED_LINE);
            }
        }

        // 当前输入（过滤文字）+ 光标
        int[] origin = inputOrigin();
        String text = model.text();
        int maxW = Math.max(0, getX() + width - CHIP_PAD - origin[0]);
        String shown = PmTheme.clip(f, text, maxW);
        if (!shown.isEmpty()) {
            g.drawString(f, shown, origin[0], origin[1], PmTheme.TEXT_PRIMARY, false);
        }
        if (editable && isFocused() && (Util.getMillis() / 500L) % 2L == 0L) {
            int cx = origin[0] + f.width(text.substring(0, Math.min(model.caret(), text.length())));
            g.fill(cx, origin[1] - 1, cx + 1, origin[1] + 9, PmTheme.CYAN);
        }

        // 输入了却没匹配到人：就地把原因说清楚（用户要求「只能从名单里选」，不说清等于点了没反应）
        if (editable && !text.isBlank() && candidates().isEmpty()) {
            String hint = Component.translatable("ccnr_pm.gui.target_empty").getString();
            int hw = f.width(hint);
            int hx = getX() + width - CHIP_PAD - hw;
            if (hx > origin[0] + f.width(shown) + 6) {
                g.drawString(f, hint, hx, getY() + 4, PmTheme.TEXT_DISABLED, false);
            }
        }

        // 满员：说清楚为什么打不进去了
        if (editable && model.isFull()) {
            String full = Component.translatable("ccnr_pm.gui.target_full", model.maxChips())
                    .getString();
            int fw = f.width(full);
            int fx = getX() + width - CHIP_PAD - fw;
            if (fx > getX() + CHIP_PAD) {
                g.drawString(f, full, fx, getY() + 4, PmTheme.TEXT_DISABLED, false);
            }
        }
    }

    /**
     * 候选弹层。
     *
     * <p>**由界面在最后一层调用**（弹层必须盖住所有其它元素，包括多行文本域这类 widget）。
     */
    public void renderPopup(GuiGraphics g, int mouseX, int mouseY) {
        if (!hasPopup()) return; // 焦点不在这个框里就不该有弹层（见 hasPopup 的注释）
        List<Candidate> items = candidates();
        Font f = Minecraft.getInstance().font;

        int[] box = popupRect(items);
        PmTheme.popupPanel(g, box[0], box[1], box[2], box[3]);

        int innerTop = box[1] + 2;
        int innerBottom = box[3] - 2;
        int visible = visibleRows(innerBottom - innerTop);
        int maxOffset = Math.max(0, items.size() - visible);
        model.setScroll(Math.min(model.scroll(), maxOffset));
        int idx = Math.max(0, Math.min(model.highlight(), items.size() - 1));

        for (int i = 0; i < visible; i++) {
            int at = model.scroll() + i;
            if (at >= items.size()) break;
            int ry1 = innerTop + i * POPUP_ROW_H;
            int ry2 = ry1 + POPUP_ROW_H;
            boolean hovered = mouseX >= box[0] && mouseX < box[2] && mouseY >= ry1 && mouseY < ry2;
            boolean current = at == idx;
            PmTheme.popupRow(g, box[0] + 1, ry1, box[2] - 1, ry2, current, hovered);
            g.drawString(
                    f,
                    PmTheme.clip(f, items.get(at).name(), (box[2] - 8) - box[0]),
                    box[0] + 4,
                    ry1 + 2,
                    PmTheme.popupRowText(current, hovered),
                    false);
        }
        if (items.size() > visible) {
            PmScrollbar.draw(g, box[2] - 6, innerTop, innerBottom, items.size(), visible, model.scroll());
        }
    }

    private int visibleRows(int innerHeight) {
        return Math.max(1, Math.min(POPUP_ROWS, innerHeight / POPUP_ROW_H));
    }

    /**
     * 弹层矩形：**渲染与命中判定共用这一处**（含宽度测量）。
     *
     * <p>曾经在命中处用宽度 0 去算同一个矩形——那等于第二份坐标，症状是「点得到的行和看到的行错位」。
     */
    private int[] popupRect(List<Candidate> items) {
        Font f = Minecraft.getInstance().font;
        int widest = 0;
        for (Candidate c : items) widest = Math.max(widest, f.width(c.name()));
        int w = Math.min(Math.max(widest + 12, width), Math.max(width, width * 2));
        int rows = Math.min(POPUP_ROWS, items.size());
        int h = rows * POPUP_ROW_H + 4;
        int x1 = getX();
        int x2 = getX() + w;
        int y1 = getY() + height + 3;
        int y2 = y1 + h;
        Minecraft mc = Minecraft.getInstance();
        int limit = mc.getWindow().getGuiScaledHeight() - 4;
        if (y2 > limit) {
            // 下方放不下就翻到上面（绝不跑出屏幕：跑出去的行既看不到也点不到）
            y2 = getY() - 3;
            y1 = y2 - h;
            if (y1 < 4) {
                y1 = 4;
                y2 = y1 + h;
            }
        }
        // 右侧越界时向左贴
        if (x2 > mc.getWindow().getGuiScaledWidth() - 4) {
            int shift = x2 - (mc.getWindow().getGuiScaledWidth() - 4);
            x1 -= shift;
            x2 -= shift;
        }
        return new int[] {x1, y1, x2, y2};
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        output.add(NarratedElementType.TITLE, Component.translatable("ccnr_pm.gui.field.targets"));
        output.add(NarratedElementType.HINT, Component.literal(model.value()));
    }

    // ------------------------------------------------------------------
    // 输入
    // ------------------------------------------------------------------

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (!editable) return false;
        boolean inBox = mx >= getX() && mx < getX() + width && my >= getY() && my < getY() + height;

        // 弹层打开时：点到候选/滚动条＝就地处理；点到输入框＝收起候选但仍是本框的点击；
        // 点到别处＝只收弹层，并把这次点击**让给玩家真正点到的控件**。
        // （曾经一律 return true 吞掉，表现为「弹层一出来，点别的输入框要点两次才点得进去」。）
        if (hasPopup()) {
            List<Candidate> items = candidates();
            int[] box = popupRect(items);
            int innerTop = box[1] + 2;
            int innerBottom = box[3] - 2;
            int visible = visibleRows(innerBottom - innerTop);
            int fromBar = PmScrollbar.clickV(
                    mx,
                    my,
                    box[2] - 6,
                    box[2],
                    innerTop,
                    innerBottom,
                    items.size(),
                    visible,
                    model.scroll(),
                    SCROLL_ID);
            if (fromBar >= 0) {
                model.setScroll(fromBar);
                return true;
            }
            if (mx >= box[0] && mx < box[2] && my >= innerTop && my < innerBottom) {
                int row = (int) (my - innerTop) / POPUP_ROW_H;
                int at = model.scroll() + row;
                if (at >= 0 && at < items.size() && button == 0) {
                    model.addChip(items.get(at));
                    return true;
                }
            }
            if (!inBox) {
                popupSuppressed = true; // 过滤词留着：点回输入框时候选会回来
                return false;
            }
            popupSuppressed = false;
            model.end();
            return true;
        }

        if (!inBox) return false;
        popupSuppressed = false; // 点回输入框：把候选叫回来
        setFocused(true);

        // 点 × = 删掉那个胶囊（用同一处坐标）
        for (int[] r : chipRects()) {
            int[] xRect = closeRect(r);
            if (mx >= xRect[0] && mx < xRect[2] && my >= xRect[1] && my < xRect[3]) {
                model.removeChip(r[4]);
                return true;
            }
        }
        // 点在胶囊上（非 ×）：把光标移到末尾，方便接着打字
        model.end();
        return true;
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        if (!hasPopup()) return false;
        List<Candidate> items = candidates();
        int[] box = popupRect(items);
        int innerTop = box[1] + 2;
        int visible = visibleRows(box[3] - 2 - innerTop);
        int maxOffset = Math.max(0, items.size() - visible);
        model.setScroll(Math.max(0, Math.min(maxOffset, model.scroll() + (delta > 0 ? -1 : 1))));
        return true; // 弹层打开时吞掉滚轮，不让下层跟着滚
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        if (!hasPopup() || !PmScrollbar.isDragging()) return false;
        List<Candidate> items = candidates();
        int next = PmScrollbar.dragV(SCROLL_ID, my);
        if (next >= 0) {
            int[] box = popupRect(items);
            int visible = visibleRows(box[3] - 2 - (box[1] + 2));
            model.setScroll(Math.min(next, Math.max(0, items.size() - visible)));
        }
        return true;
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        PmScrollbar.endDrag();
        return false;
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        // 焦点检查不是多余的：原版只把 charTyped 发给 getFocused()，但一旦有人（模组/其它界面）
        // 直接调到这个控件，没有这道检查就会「没焦点也在收字」。
        if (!editable || !isFocused()) return false;
        boolean ok = model.insert(codePoint);
        if (ok) popupSuppressed = false; // 继续打字 → 候选重新出现
        return ok;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (!editable || !isFocused()) return false;
        boolean ctrl = (modifiers & 0x2) != 0;

        // 弹层打开时：上下选择、回车/Tab 确认、Esc 只关弹层（Esc 不关整个面板）
        if (hasPopup()) {
            List<Candidate> items = candidates();
            if (keyCode == KEY_ESC) {
                popupSuppressed = true; // 只收弹层，不动玩家已经敲进去的过滤词
                return true;
            }
            if (keyCode == KEY_UP) {
                model.moveHighlight(-1, items.size());
                return true;
            }
            if (keyCode == KEY_DOWN) {
                model.moveHighlight(1, items.size());
                return true;
            }
            if (keyCode == KEY_ENTER || keyCode == KEY_TAB) {
                int at = Math.max(0, Math.min(model.highlight(), items.size() - 1));
                model.addChip(items.get(at));
                return true;
            }
        }

        switch (keyCode) {
            case KEY_BACKSPACE -> {
                boolean changed = model.backspace();
                if (changed) popupSuppressed = false;
                return changed;
            }
            case KEY_DELETE -> {
                return model.deleteForward();
            }
            case KEY_LEFT -> {
                model.moveCaret(-1);
                return true;
            }
            case KEY_RIGHT -> {
                model.moveCaret(1);
                return true;
            }
            case KEY_HOME -> {
                model.home();
                return true;
            }
            case KEY_END -> {
                model.end();
                return true;
            }
            case KEY_V -> {
                if (!ctrl) return false;
                model.setText(Minecraft.getInstance().keyboardHandler.getClipboard());
                return true;
            }
            default -> {
                return false;
            }
        }
    }
}
