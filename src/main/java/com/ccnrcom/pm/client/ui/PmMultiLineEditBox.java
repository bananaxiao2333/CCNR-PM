/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.pm.client.ui;

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
 * 多行文本输入框（原版没有这个控件，本模组自己实现）。
 *
 * <p>为什么要自己写：原版 {@code EditBox} 是单行的，「详细描述」这种要写好几行说明的字段
 * 挤在一行里既难写也难回看。原版没有任何多行输入控件，只能自己实现。
 *
 * <p>设计取舍：
 * <ul>
 *   <li><b>断行规则复用 {@link PmTextLayout#wrapRows}</b>，不另写一套。两套断行必然漂移，
 *       症状是「画出来的行」与「光标所在的行」对不上。</li>
 *   <li><b>始终为滚动条预留宽度</b>（即使当前不溢出）。否则内容变长时文本会突然变窄、
 *       已输入的文字跟着重排跳动。稳定的窄一点，好过抖一下。</li>
 *   <li><b>文本模型在 {@link PmTextBuffer}</b>，本类只负责渲染与输入。光标/选区的边界条件
 *       因此可以在没有客户端环境的 CI 里被精确测试。</li>
 * </ul>
 *
 * <p>支持的按键：输入法字符、回车换行、退格、Delete、方向键（配合 Shift 扩选）、
 * Home/End、PageUp/PageDown、Ctrl+A 全选、Ctrl+C/X/V 复制剪切粘贴、点击与拖拽选择。
 */
public class PmMultiLineEditBox extends AbstractWidget {

    /** 行高（字体行高 9 + 1 行距）。 */
    public static final int LINE_H = 10;

    private static final int PAD_X = 4;
    private static final int PAD_Y = 3;
    /** 滚动条预留宽度（见类注释：恒定预留，避免文本重排跳动）。 */
    private static final int SCROLL_RESERVE = 8;

    private static final int KEY_ENTER = 257;
    private static final int KEY_BACKSPACE = 259;
    private static final int KEY_DELETE = 261;
    private static final int KEY_RIGHT = 262;
    private static final int KEY_LEFT = 263;
    private static final int KEY_DOWN = 264;
    private static final int KEY_UP = 265;
    private static final int KEY_PAGE_UP = 266;
    private static final int KEY_PAGE_DOWN = 267;
    private static final int KEY_HOME = 268;
    private static final int KEY_END = 269;
    private static final int KEY_A = 65;
    private static final int KEY_C = 67;
    private static final int KEY_V = 86;
    private static final int KEY_X = 88;

    private final Font font;
    private final int scrollbarId;
    private final PmTextBuffer buffer;

    private boolean editable = true;
    private int scrollRow;
    private boolean dragging;
    private long blinkStart = Util.getMillis();

    /** 最近一次布局算出的行区间（输入与渲染共用）。 */
    private List<PmTextLayout.Row> rows = List.of(new PmTextLayout.Row(0, 0));

    public PmMultiLineEditBox(
            Font font, int x, int y, int width, int height, int maxLength, int scrollbarId, Component narration) {
        super(x, y, width, height, narration);
        this.font = font;
        this.scrollbarId = scrollbarId;
        this.buffer = new PmTextBuffer(maxLength);
    }

    // ------------------------------------------------------------------
    // 值与状态
    // ------------------------------------------------------------------

    public String getValue() {
        return buffer.text();
    }

    public void setValue(String value) {
        buffer.setText(value);
        scrollRow = 0;
        relayout();
    }

    public void setEditable(boolean editable) {
        this.editable = editable;
        this.active = editable;
    }

    public boolean isEditable() {
        return editable;
    }

    /** 供外部预填/清空后同步布局。 */
    public void refreshLayout() {
        relayout();
    }

    // ------------------------------------------------------------------
    // 布局
    // ------------------------------------------------------------------

    private int textWidth() {
        return Math.max(16, getWidth() - PAD_X * 2 - SCROLL_RESERVE);
    }

    private int visibleRows() {
        return Math.max(1, (getHeight() - PAD_Y * 2) / LINE_H);
    }

    private void relayout() {
        rows = PmTextLayout.wrapRows(buffer.text(), textWidth(), font::width);
        int maxScroll = Math.max(0, rows.size() - visibleRows());
        scrollRow = Math.max(0, Math.min(scrollRow, maxScroll));
    }

    private int rowTop(int visualRow) {
        return getY() + PAD_Y + (visualRow - scrollRow) * LINE_H;
    }

    private int rowX(int index) {
        int rowIdx = PmTextLayout.rowIndex(rows, index);
        return getX() + PAD_X + PmTextLayout.columnWidth(buffer.text(), rows.get(rowIdx), index, font::width);
    }

    /** 保证光标可见：必要时滚动。 */
    private void ensureCursorVisible() {
        int rowIdx = PmTextLayout.rowIndex(rows, buffer.cursor());
        int visible = visibleRows();
        if (rowIdx < scrollRow) {
            scrollRow = rowIdx;
        } else if (rowIdx >= scrollRow + visible) {
            scrollRow = rowIdx - visible + 1;
        }
        int maxScroll = Math.max(0, rows.size() - visible);
        scrollRow = Math.max(0, Math.min(scrollRow, maxScroll));
    }

    private void touch() {
        blinkStart = Util.getMillis();
    }

    private boolean cursorVisible() {
        return isFocused() && (Util.getMillis() - blinkStart) % 1000L < 500L;
    }

    // ------------------------------------------------------------------
    // 渲染
    // ------------------------------------------------------------------

    @Override
    protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        relayout();

        // 底与边框：聚焦时描边提亮，让「现在在往哪个框里打字」一目了然
        g.fill(getX(), getY(), getX() + getWidth(), getY() + getHeight(), PmTheme.SURFACE_SUNKEN);
        PmTheme.outlined(
                g,
                getX(),
                getY(),
                getX() + getWidth(),
                getY() + getHeight(),
                isFocused() ? PmTheme.PANEL_BORDER_BRIGHT : PmTheme.PANEL_BORDER);

        if (buffer.isEmpty() && !isFocused()) {
            String ph = getMessage().getString();
            g.drawString(
                    font,
                    PmTheme.clip(font, ph, textWidth()),
                    getX() + PAD_X,
                    getY() + PAD_Y + 1,
                    PmTheme.TEXT_DIM,
                    false);
            return;
        }

        int bodyTop = getY() + PAD_Y;
        int bodyBottom = getY() + getHeight() - PAD_Y;

        g.enableScissor(getX() + 1, bodyTop, getX() + getWidth() - 1, bodyBottom);

        String text = buffer.text();
        int selStart = buffer.selectionStart();
        int selEnd = buffer.selectionEnd();
        int visible = visibleRows();

        for (int i = 0; i < visible; i++) {
            int rowIdx = scrollRow + i;
            if (rowIdx >= rows.size()) break;
            PmTextLayout.Row row = rows.get(rowIdx);
            int ty = rowTop(rowIdx);

            // 选区高亮（逐行与选区求交，只画可见部分）
            if (buffer.hasSelection() && selStart < row.end() && selEnd > row.start()) {
                int from = Math.max(selStart, row.start());
                int to = Math.min(selEnd, row.end());
                int x1 = getX() + PAD_X + PmTextLayout.columnWidth(text, row, from, font::width);
                int x2 = getX() + PAD_X + PmTextLayout.columnWidth(text, row, to, font::width);
                if (x2 <= x1) x2 = x1 + 2; // 空行被选中时给一个可见宽度
                g.fill(x1, ty - 1, x2, ty + font.lineHeight - 1, PmTheme.alphaBlend(PmTheme.CYAN, 0x33));
            }

            if (row.length() > 0) {
                g.drawString(
                        font, text.substring(row.start(), row.end()), getX() + PAD_X, ty, PmTheme.TEXT_PRIMARY, false);
            }
        }

        // 光标
        if (cursorVisible()) {
            int rowIdx = PmTextLayout.rowIndex(rows, buffer.cursor());
            int cx = rowX(buffer.cursor());
            int cy = rowTop(rowIdx);
            g.fill(cx, cy - 1, cx + 1, cy + font.lineHeight - 1, PmTheme.CYAN);
        }

        g.disableScissor();

        // 滚动条：只在溢出时出现（PmScrollbar 内部处理不溢出时的表现）
        if (rows.size() > visible) {
            PmScrollbar.draw(
                    g, getX() + getWidth() - SCROLL_RESERVE + 2, bodyTop, bodyBottom, rows.size(), visible, scrollRow);
        }
    }

    // ------------------------------------------------------------------
    // 输入
    // ------------------------------------------------------------------

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        if (!isEditable() || !isFocused() || !isActive()) return false;
        // 控制字符（含回车）不走这里：回车由 keyPressed 处理，其余控制字符没有可输入的意义
        if (codePoint < ' ') return false;
        buffer.insert(String.valueOf(codePoint));
        touch();
        ensureCursorVisible();
        return true;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (!isFocused() || !isActive()) return false;
        boolean select = net.minecraft.client.gui.screens.Screen.hasShiftDown();
        boolean ctrl = net.minecraft.client.gui.screens.Screen.hasControlDown();

        if (ctrl) {
            switch (keyCode) {
                case KEY_A -> {
                    buffer.selectAll();
                    touch();
                    return true;
                }
                case KEY_C -> {
                    if (buffer.hasSelection()) setClipboard(buffer.selectedText());
                    return true;
                }
                case KEY_X -> {
                    if (isEditable() && buffer.hasSelection()) {
                        setClipboard(buffer.selectedText());
                        buffer.deleteSelection();
                        touch();
                        ensureCursorVisible();
                    }
                    return true;
                }
                case KEY_V -> {
                    if (isEditable()) {
                        buffer.replaceSelection(getClipboard());
                        touch();
                        ensureCursorVisible();
                    }
                    return true;
                }
                default -> {
                    // 继续走下面的普通键处理
                }
            }
        }

        switch (keyCode) {
            case KEY_ENTER -> {
                if (!isEditable()) return true;
                buffer.newline();
                touch();
                ensureCursorVisible();
                return true;
            }
            case KEY_BACKSPACE -> {
                if (!isEditable()) return true;
                buffer.backspace();
                touch();
                ensureCursorVisible();
                return true;
            }
            case KEY_DELETE -> {
                if (!isEditable()) return true;
                buffer.deleteForward();
                touch();
                ensureCursorVisible();
                return true;
            }
            case KEY_LEFT -> {
                buffer.move(-1, select);
                touch();
                ensureCursorVisible();
                return true;
            }
            case KEY_RIGHT -> {
                buffer.move(1, select);
                touch();
                ensureCursorVisible();
                return true;
            }
            case KEY_UP -> {
                buffer.moveVertical(rows, -1, font::width, select);
                touch();
                ensureCursorVisible();
                return true;
            }
            case KEY_DOWN -> {
                buffer.moveVertical(rows, 1, font::width, select);
                touch();
                ensureCursorVisible();
                return true;
            }
            case KEY_HOME -> {
                buffer.moveLineEdge(rows, -1, select);
                touch();
                ensureCursorVisible();
                return true;
            }
            case KEY_END -> {
                buffer.moveLineEdge(rows, 1, select);
                touch();
                ensureCursorVisible();
                return true;
            }
            case KEY_PAGE_UP -> {
                buffer.moveVertical(rows, -visibleRows(), font::width, select);
                touch();
                ensureCursorVisible();
                return true;
            }
            case KEY_PAGE_DOWN -> {
                buffer.moveVertical(rows, visibleRows(), font::width, select);
                touch();
                ensureCursorVisible();
                return true;
            }
            default -> {
                // Tab 等按键故意放行，交给上层做焦点切换
                return false;
            }
        }
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (!isActive() || !this.visible || button != 0) return false;
        if (!isMouseOver(mx, my)) return false;
        setFocused(true);
        dragging = true;
        touch();
        buffer.setCursor(indexAt(mx, my), false);
        ensureCursorVisible();
        return true;
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        if (!dragging) return false;
        // 拖到框外时仍按最近的行/列定位，这样「往外拖」能持续扩选而不是停住
        buffer.setCursor(indexAt(mx, my), true);
        touch();
        ensureCursorVisible();
        return true;
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        boolean was = dragging;
        dragging = false;
        return was;
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        if (!isMouseOver(mx, my)) return false;
        relayout();
        int maxScroll = Math.max(0, rows.size() - visibleRows());
        if (maxScroll <= 0) return false;
        // delta 约为 ±1：先取方向再步进，避免 delta/10 在整数除法下恒为 0
        scrollRow = Math.max(0, Math.min(maxScroll, scrollRow + (delta > 0 ? -1 : 1)));
        return true;
    }

    @Override
    public void setFocused(boolean focused) {
        // 失焦时把选区收掉：否则重新聚焦时会出现「莫名其妙的高亮」
        if (!focused) dragging = false;
        super.setFocused(focused);
    }

    /** 屏幕坐标 → 字符索引。 */
    private int indexAt(double mx, double my) {
        relayout();
        int rowIdx = (int) Math.floor((my - (getY() + PAD_Y)) / LINE_H) + scrollRow;
        rowIdx = Math.max(0, Math.min(rows.size() - 1, rowIdx));
        PmTextLayout.Row row = rows.get(rowIdx);
        int localX = (int) Math.round(mx) - (getX() + PAD_X);
        return PmTextLayout.indexAtColumn(buffer.text(), row, Math.max(0, localX), font::width);
    }

    private static String getClipboard() {
        try {
            String s = Minecraft.getInstance().keyboardHandler.getClipboard();
            return s == null ? "" : s;
        } catch (Exception e) {
            return "";
        }
    }

    private static void setClipboard(String s) {
        try {
            Minecraft.getInstance().keyboardHandler.setClipboard(s == null ? "" : s);
        } catch (Exception ignored) {
            // 剪贴板不可用（某些平台/无头环境）：静默忽略，不影响其它编辑操作
        }
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        output.add(NarratedElementType.TITLE, getMessage());
        if (!buffer.isEmpty()) {
            output.add(NarratedElementType.HINT, Component.literal(buffer.text()));
        }
    }
}
