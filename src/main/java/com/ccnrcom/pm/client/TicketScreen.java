/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.pm.client;

import com.ccnrcom.pm.client.ui.PmButton;
import com.ccnrcom.pm.client.ui.PmChipInput;
import com.ccnrcom.pm.client.ui.PmChipModel;
import com.ccnrcom.pm.client.ui.PmMultiLineEditBox;
import com.ccnrcom.pm.client.ui.PmScrollbar;
import com.ccnrcom.pm.client.ui.PmTheme;
import com.ccnrcom.pm.network.PmChannel;
import com.ccnrcom.pm.network.PmPackets;
import com.ccnrcom.pm.ticket.CategoryRegistry;
import com.ccnrcom.pm.ticket.ReportDraft;
import com.ccnrcom.pm.ticket.ReportValidator;
import com.ccnrcom.pm.ticket.TicketCategory;
import com.ccnrcom.pm.ticket.TicketService;
import com.ccnrcom.pm.util.JsonUtil;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * 举报工单面板（玩家侧）。
 *
 * <h2>它是什么</h2>
 * 玩家执行 {@code /a <消息>}（或 {@code /report}）后弹出。面板把**那条消息预填为举报内容**，
 * 再让玩家补齐「关联玩家 / 举报类别 / 详细描述」三项，点提交即建单。
 * 设计意图：把「想用管理员频道喊话」这个动作顺势转成一条举报，
 * 既不给出权限回绝的挫败感，也把内容留在工单队列里而不是丢掉。
 *
 * <h2>必填 / 选填</h2>
 * <ul>
 *   <li><b>必填</b>：举报内容、举报类别</li>
 *   <li><b>选填</b>：关联玩家（可多个、支持补全）、详细描述（多行）</li>
 * </ul>
 * 关联玩家选填是刻意的：玩家常常只有一句「有人在刷屏」而说不出是谁，
 * 强制填写只会把人挡在门外——而这恰恰是最需要被记录下来的举报。
 *
 * <h2>服务端权威</h2>
 * 面板里的校验只为即时反馈；提交后服务端会用**同一套** {@link ReportValidator} 重跑一遍，
 * 并且还要额外核对冷却与并发上限。结论以服务端回包为准。
 */
public final class TicketScreen extends Screen {

    private static final Logger LOGGER = LogManager.getLogger("ccnr_pm");

    // ---- 布局常量 ----
    private static final int PAD = 14;
    private static final int ROW_H = 12;
    private static final int BOX_H = 18;

    /**
     * 关联玩家胶囊框占几行。
     *
     * <p>2 行而不是 1 行：最多 8 个胶囊（{@code ReportValidator.MAX_TARGETS}），
     * 窄窗口下一行放不下就会换行——固定 2 行让布局**不随内容跳动**，
     * 代价是详细描述少了 18px（面板纵向本来就紧张，这个交换是值的）。
     */
    private static final int CHIP_ROWS = 2;

    /** 胶囊框高度。 */
    private static final int CHIP_BOX_H = CHIP_ROWS * BOX_H;

    private static final int BTN_H = 20;
    /** 弹出层最多显示行数。 */
    private static final int POPUP_MAX_ROWS = 6;
    /** 给状态/错误区预留的高度（约 3 行），保证错误提示永远压不到按钮上。 */
    private static final int ERROR_RESERVE = 34;
    /** 类别下拉与补全弹层的滚动条 id（本界面内唯一；拖拽派发按 id 匹配）。 */
    private static final int SCROLL_ID_POPUP = 1;
    /** 详细描述多行框的滚动条 id。 */
    private static final int SCROLL_ID_DETAIL = 2;

    private static final int KEY_ESC = 256;
    private static final int KEY_ENTER = 257;
    private static final int KEY_TAB = 258;
    private static final int KEY_DOWN = 264;
    private static final int KEY_UP = 265;

    /** 当前打开的实例；用于把服务端回包路由回面板。 */
    private static volatile TicketScreen open;

    private final Screen parent;
    private final String prefill;
    private final List<TicketService.OnlinePlayer> roster;
    private final List<TicketCategory> categories;
    private final int maxContent;
    private final int maxDetail;

    private EditBox contentBox;
    private PmMultiLineEditBox detailBox;
    private PmChipInput targetChip;

    /**
     * 关联玩家的胶囊模型（跨 init 保留）。
     *
     * <p>窗口缩放会重建整个界面，胶囊必须活在 widget 之外——否则一缩放玩家刚选的人就没了。
     */
    private final PmChipModel targetModel = new PmChipModel(ReportValidator.MAX_TARGETS);

    private boolean firstInit = true;

    /** 「打字事件没人接收」的日志只报一次，避免按一次键刷一行。 */
    private boolean warnedUnhandledChar;

    private String categoryId = "";

    /**
     * 弹出层：类别下拉。
     *
     * <p>关联玩家的候选弹层不在这里——它由 {@code PmChipInput} 自己持有，
     * 因为它的几何取决于胶囊占了几行（见该类的 {@code popupRect}）。
     */
    private enum Popup {
        NONE,
        CATEGORY
    }

    private Popup openPopup = Popup.NONE;
    private int popupScroll;
    private int popupIndex;
    private int popupX1;
    private int popupX2;
    private int popupTop;

    /** 提交状态。 */
    private boolean awaiting;

    private boolean submitted;
    private String resultLabel = "";
    private List<Component> errors = List.of();

    // ---- 几何 ----
    private int px1;
    private int py1;
    private int px2;
    private int py2;

    public TicketScreen(
            String prefill,
            List<TicketService.OnlinePlayer> roster,
            List<TicketCategory> categories,
            int maxContent,
            int maxDetail) {
        super(Component.translatable("ccnr_pm.gui.title"));
        this.parent = Minecraft.getInstance().screen;
        this.prefill = prefill == null ? "" : prefill;
        this.roster = roster == null ? List.of() : roster;
        this.categories = categories == null ? List.of() : categories;
        this.maxContent = Math.max(16, maxContent);
        this.maxDetail = Math.max(0, maxDetail);
    }

    /** 当前打开的面板（无则 null）。 */
    public static TicketScreen open() {
        return open;
    }

    @Override
    protected void init() {
        open = this;

        int pw = Math.max(380, Math.min(width - 40, 560));
        int ph = Math.max(360, Math.min(height - 40, 480));
        // 窗口比最小尺寸还小时贴边显示，避免面板反向溢出到屏幕外
        pw = Math.min(pw, Math.max(200, width - 8));
        ph = Math.min(ph, Math.max(180, height - 8));
        px1 = Math.max(0, (width - pw) / 2);
        py1 = Math.max(0, (height - ph) / 2);
        px2 = px1 + pw;
        py2 = py1 + ph;

        // 窗口缩放会重建界面：先把玩家已经敲进去的内容接过来，不能丢
        String content = contentBox == null ? prefill : contentBox.getValue();
        String detail = detailBox == null ? "" : detailBox.getValue();
        // 焦点也要接过来：焦点留在**旧实例**上时，打字会打到一个已经不在屏幕上的输入框里，
        // 玩家看到的就是「框点得亮、键盘却在往别处打字」（PmChipInput 没有 isFocused 检查时同理）
        boolean chipHadFocus = targetChip != null && targetChip.isFocused();
        boolean detailHadFocus = detailBox != null && detailBox.isFocused();

        int boxW = (px2 - px1) - PAD * 2;
        contentBox = mkBox(px1 + PAD, contentBoxY(), boxW, maxContent, content);
        // 关联玩家：胶囊输入框（只能从在线名单里选，见 PmChipInput 的类注释）
        targetChip = new PmChipInput(font, px1 + PAD, targetBoxY(), Math.max(60, boxW), CHIP_ROWS, targetModel, roster);
        targetChip.setEditable(!submitted && !awaiting);
        addRenderableWidget(targetChip);

        int detailH = Math.max(40, detailBottom() - detailBoxY());
        detailBox = new PmMultiLineEditBox(
                font,
                px1 + PAD,
                detailBoxY(),
                boxW,
                detailH,
                Math.max(1, maxDetail),
                SCROLL_ID_DETAIL,
                detailPlaceholder());
        detailBox.setValue(detail);
        detailBox.setEditable(maxDetail > 0);
        addRenderableWidget(detailBox);

        if (firstInit) {
            firstInit = false;
            setFocused(contentBox);
            contentBox.setFocused(true);
        } else if (chipHadFocus) {
            setFocused(targetChip);
        } else if (detailHadFocus) {
            setFocused(detailBox);
        } else {
            // 原来焦点在内容框（或无处可寻）：内容框是第一个要填的字段
            setFocused(contentBox);
        }
    }

    private EditBox mkBox(int x, int y, int w, int maxLen, String value) {
        EditBox box = new EditBox(font, x, y, Math.max(60, w), BOX_H, Component.empty());
        box.setMaxLength(Math.max(1, maxLen));
        box.setValue(value == null ? "" : value);
        box.setTextColor(PmTheme.CYAN);
        addRenderableWidget(box);
        return box;
    }

    private Component detailPlaceholder() {
        return maxDetail > 0
                ? Component.translatable("ccnr_pm.gui.detail_placeholder")
                : Component.translatable("ccnr_pm.gui.detail_disabled");
    }

    // ---- 纵向布局（相对面板顶边，便于统一调整）----
    private int contentLabelY() {
        return py1 + 32;
    }

    private int contentBoxY() {
        return py1 + 42;
    }

    private int hintY() {
        return py1 + 64;
    }

    private int targetLabelY() {
        return py1 + 80;
    }

    private int targetBoxY() {
        return py1 + 90;
    }

    private int categoryLabelY() {
        return py1 + 118 + (CHIP_BOX_H - BOX_H);
    }

    private int categoryBoxY() {
        return py1 + 128 + (CHIP_BOX_H - BOX_H);
    }

    private int detailLabelY() {
        return py1 + 156 + (CHIP_BOX_H - BOX_H);
    }

    private int detailBoxY() {
        return py1 + 166 + (CHIP_BOX_H - BOX_H);
    }

    private int buttonY1() {
        return py2 - 10 - BTN_H;
    }

    private int buttonY2() {
        return py2 - 10;
    }

    /** 详细描述框底边：一路顶到状态/错误区之上，把面板剩余高度全给它。 */
    private int detailBottom() {
        return buttonY1() - 6 - ERROR_RESERVE;
    }

    private int statusY() {
        return detailBottom() + 4;
    }

    private int[] cancelRect() {
        int bw = ((px2 - px1) - PAD * 2 - 10) / 2;
        return new int[] {px1 + PAD, buttonY1(), px1 + PAD + bw, buttonY2()};
    }

    private int[] submitRect() {
        int bw = ((px2 - px1) - PAD * 2 - 10) / 2;
        return new int[] {px2 - PAD - bw, buttonY1(), px2 - PAD, buttonY2()};
    }

    private int[] targetRect() {
        return new int[] {px1 + PAD, targetBoxY(), px2 - PAD, targetBoxY() + CHIP_BOX_H};
    }

    private int[] categoryRect() {
        return new int[] {px1 + PAD, categoryBoxY(), px2 - PAD, categoryBoxY() + BOX_H};
    }

    // ----------------------------------------------------------------
    // 渲染
    // ----------------------------------------------------------------

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderBackground(g);

        PmTheme.terminalFrame(g, px1, py1, px2, py2, PmTheme.RADIUS_LARGE);
        PmTheme.gridOverlay(g, px1 + 1, py1 + 1, px2 - 1, py2 - 1);

        String title = PmTheme.tag("CCNR") + " " + tr("ccnr_pm.gui.title");
        g.drawString(font, title, px1 + PAD, py1 + 11, PmTheme.TEXT_PRIMARY, false);
        String right = submitted ? resultLabel : PmTheme.section("DRAFT");
        g.drawString(font, right, px2 - PAD - font.width(right), py1 + 11, PmTheme.CYAN_DIM, false);
        PmTheme.listHeaderRule(g, px1 + PAD, px2 - PAD, py1 + 26);

        fieldLabel(g, "ccnr_pm.gui.field.content", contentLabelY());
        // 关联玩家的填写说明放在标签行内（右侧、更暗），省下一整行高度给详细描述框
        fieldLabelWithHint(g, "ccnr_pm.gui.field.targets", "ccnr_pm.gui.target_hint", targetLabelY());
        fieldLabel(g, "ccnr_pm.gui.field.category", categoryLabelY());
        fieldLabel(g, "ccnr_pm.gui.field.detail", detailLabelY());

        String hint = submitted || awaiting ? "" : tr("ccnr_pm.gui.content_hint");
        if (!hint.isEmpty()) {
            g.drawString(
                    font, PmTheme.clip(font, hint, (px2 - px1) - PAD * 2), px1 + PAD, hintY(), PmTheme.TEXT_DIM, false);
        }

        // 类别下拉（手绘：显示当前选择 + 展开标记）
        drawSelector(
                g,
                categoryRect(),
                categoryId.isEmpty() ? tr("ccnr_pm.gui.select_category") : categoryLabel(categoryId),
                mouseX,
                mouseY);

        // 输入框 widget（单行内容 + 单行关联玩家 + 多行详细描述）
        super.render(g, mouseX, mouseY, partialTick);

        renderStatus(g);
        renderButtons(g, mouseX, mouseY);
        // 弹出层必须最后绘制，其余元素都不得盖住它（类别下拉与胶囊候选都算弹层）
        renderPopup(g, mouseX, mouseY);
        if (targetChip != null) targetChip.renderPopup(g, mouseX, mouseY);

        PmTheme.scanlines(g, px1 + 1, py1 + 1, px2 - 1, py2 - 1);
    }

    private void fieldLabel(GuiGraphics g, String key, int y) {
        g.drawString(font, PmTheme.section(tr(key)), px1 + PAD, y, PmTheme.TEXT_DIM, false);
    }

    /**
     * 标签 + 行内提示。
     *
     * <p>提示画在同一行的右侧并用更暗的颜色：既能省下一整行高度（面板纵向很紧张），
     * 又因为「暗一档」而不会被误当成标签本身。空间不足时直接不画，绝不压到标签上。
     */
    private void fieldLabelWithHint(GuiGraphics g, String key, String hintKey, int y) {
        String label = PmTheme.section(tr(key));
        g.drawString(font, label, px1 + PAD, y, PmTheme.TEXT_DIM, false);
        String hint = tr(hintKey);
        if (hint.isBlank()) return;
        int hx = px1 + PAD + font.width(label) + 8;
        int avail = (px2 - PAD) - hx;
        if (avail < 24) return;
        g.drawString(font, PmTheme.clip(font, hint, avail), hx, y, PmTheme.TEXT_DISABLED, false);
    }

    private void drawSelector(GuiGraphics g, int[] r, String text, int mouseX, int mouseY) {
        boolean hovered = openPopup == Popup.NONE && PmButton.hit(mouseX, mouseY, r[0], r[1], r[2], r[3]);
        PmTheme.controlBox(g, r[0], r[1], r[2], r[3], openPopup == Popup.CATEGORY);
        int ink = text.startsWith("ccnr_pm.") ? PmTheme.TEXT_DISABLED : PmTheme.TEXT_PRIMARY;
        g.drawString(
                font,
                PmTheme.clip(font, text, (r[2] - r[0]) - 26),
                r[0] + 4,
                r[1] + 5,
                hovered ? PmTheme.CYAN : ink,
                false);
        g.drawString(font, openPopup == Popup.CATEGORY ? "^" : "v", r[2] - 12, r[1] + 5, PmTheme.CYAN_DIM, false);
    }

    private void renderStatus(GuiGraphics g) {
        int y = statusY();
        int maxW = (px2 - px1) - PAD * 2;
        int limitY = buttonY1() - 2;

        if (submitted) {
            for (String line : PmTheme.wrapText(font, tr("ccnr_pm.gui.submitted", resultLabel), maxW)) {
                if (y + 9 > limitY) break;
                g.drawString(font, line, px1 + PAD, y, PmTheme.CYAN, false);
                y += ROW_H;
            }
            // 新工单必然是「待处理」：再把状态单独用**状态色**画一行，
            // 让玩家一眼知道自己这条处在哪个环节（用户要求状态文字要能分辨）
            if (y + 9 <= limitY) {
                String statusLine = tr(
                        "ccnr_pm.gui.submitted_status",
                        com.ccnrcom.pm.client.ui.PmNames.statusName(com.ccnrcom.pm.ticket.TicketStatus.OPEN.id()));
                g.drawString(
                        font,
                        PmTheme.clip(font, statusLine, maxW),
                        px1 + PAD,
                        y,
                        PmTheme.statusColor(com.ccnrcom.pm.ticket.TicketStatus.OPEN.id()),
                        false);
            }
            return;
        }
        if (awaiting) {
            g.drawString(font, tr("ccnr_pm.gui.submitting"), px1 + PAD, y, PmTheme.TEXT_SECONDARY, false);
            return;
        }
        for (Component err : errors) {
            for (String line : PmTheme.wrapText(font, err.getString(), maxW)) {
                if (y + 9 > limitY) return;
                g.drawString(font, line, px1 + PAD, y, PmTheme.RED_LINE, false);
                y += ROW_H;
            }
        }
    }

    private void renderButtons(GuiGraphics g, int mouseX, int mouseY) {
        int[] cancel = cancelRect();
        int[] submit = submitRect();

        String cancelLabel = submitted ? tr("ccnr_pm.gui.close") : tr("ccnr_pm.gui.cancel");
        boolean cancelHover = PmButton.hit(mouseX, mouseY, cancel[0], cancel[1], cancel[2], cancel[3]);
        PmButton.draw(
                g,
                font,
                cancel[0],
                cancel[1],
                cancel[2],
                cancel[3],
                cancelLabel,
                PmButton.Variant.SECONDARY,
                cancelHover,
                true);

        if (submitted) return;

        boolean enabled = !awaiting;
        boolean submitHover = enabled
                && openPopup == Popup.NONE
                && PmButton.hit(mouseX, mouseY, submit[0], submit[1], submit[2], submit[3]);
        PmButton.draw(
                g,
                font,
                submit[0],
                submit[1],
                submit[2],
                submit[3],
                tr("ccnr_pm.gui.submit"),
                PmButton.Variant.PRIMARY,
                submitHover,
                enabled);
    }

    // ----------------------------------------------------------------
    // 弹出层（类别下拉 / 关联玩家补全）
    // ----------------------------------------------------------------

    /** 弹出层的一个候选：{@code id} 是回传/写入的值，{@code label} 是显示文本。 */
    private record Option(String id, String label) {}

    /** 类别下拉的候选。 */
    private List<Option> categoryOptions() {
        List<Option> out = new ArrayList<>();
        for (TicketCategory c : categories) {
            if (c.enabled()) out.add(new Option(c.id(), categoryLabel(c)));
        }
        return out;
    }

    private List<Option> popupOptions() {
        return categoryOptions();
    }

    private int[] popupBox() {
        List<Option> items = popupOptions();
        if (items.isEmpty()) return new int[0];
        int rows = Math.min(POPUP_MAX_ROWS, items.size());
        int h = rows * ROW_H + 4;
        int y1 = popupTop;
        int y2 = y1 + h;
        // 贴住面板底边，绝不让弹层跑出面板
        int limit = py2 - 12;
        if (y2 > limit) {
            y2 = limit;
            y1 = Math.max(py1 + 30, y2 - h);
        }
        return new int[] {popupX1, y1, popupX2, y2};
    }

    private void openPopup(Popup which, int[] anchor) {
        openPopup = which;
        popupScroll = 0;
        popupIndex = 0;
        popupX1 = anchor[0];
        popupX2 = anchor[2];
        popupTop = anchor[3] + 2;
        if (which == Popup.CATEGORY) setFocused(null);
    }

    private void closePopup() {
        openPopup = Popup.NONE;
        popupScroll = 0;
        popupIndex = 0;
    }

    private void renderPopup(GuiGraphics g, int mouseX, int mouseY) {
        if (openPopup == Popup.NONE) return;
        List<Option> items = popupOptions();
        if (items.isEmpty()) {
            // 补全候选为空是常态（名字已输完）；类别为空说明数据有问题，同样收起
            closePopup();
            return;
        }
        int[] box = popupBox();
        if (box.length == 0) return;

        PmTheme.popupPanel(g, box[0], box[1], box[2], box[3]);

        int innerTop = box[1] + 2;
        int innerBottom = box[3] - 2;
        int visible = Math.max(1, (innerBottom - innerTop) / ROW_H);
        int maxOffset = Math.max(0, items.size() - visible);
        popupScroll = Math.max(0, Math.min(popupScroll, maxOffset));
        popupIndex = Math.max(0, Math.min(popupIndex, items.size() - 1));

        boolean overList = mouseX >= box[0] && mouseX < box[2] && mouseY >= box[1] && mouseY < box[3];
        int textW = (box[2] - 8) - box[0] - (items.size() > visible ? 8 : 0);

        for (int i = 0; i < visible; i++) {
            int idx = popupScroll + i;
            if (idx >= items.size()) break;
            Option opt = items.get(idx);
            int ry1 = innerTop + i * ROW_H;
            int ry2 = ry1 + ROW_H;
            boolean hovered = overList && mouseY >= ry1 && mouseY < ry2;
            boolean current = openPopup == Popup.CATEGORY ? opt.id().equals(categoryId) : idx == popupIndex;
            PmTheme.popupRow(g, box[0] + 1, ry1, box[2] - 1, ry2, current, hovered);
            g.drawString(
                    font,
                    PmTheme.clip(font, opt.label(), textW),
                    box[0] + 4,
                    ry1 + 2,
                    PmTheme.popupRowText(current, hovered),
                    false);
        }
        if (items.size() > visible) {
            PmScrollbar.draw(g, box[2] - 6, innerTop, innerBottom, items.size(), visible, popupScroll);
        }
    }

    /** 返回是否吞掉了该次点击。 */
    private boolean handlePopupClick(double mx, double my) {
        List<Option> items = popupOptions();
        int[] box = popupBox();
        if (items.isEmpty() || box.length == 0) {
            closePopup();
            return true;
        }
        int innerTop = box[1] + 2;
        int innerBottom = box[3] - 2;
        int visible = Math.max(1, (innerBottom - innerTop) / ROW_H);
        int fromBar = PmScrollbar.clickV(
                mx, my, box[2] - 6, box[2], innerTop, innerBottom, items.size(), visible, popupScroll, SCROLL_ID_POPUP);
        if (fromBar >= 0) {
            popupScroll = fromBar;
            return true;
        }
        if (mx >= box[0] && mx < box[2] && my >= innerTop && my < innerBottom) {
            int row = (int) (my - innerTop) / ROW_H;
            int idx = popupScroll + row;
            if (idx >= 0 && idx < items.size()) {
                applyOption(items.get(idx));
                return true;
            }
        }
        // 点在任何其它地方：只关弹层，不穿透到下层（弹层打开时下层不可交互）
        closePopup();
        return true;
    }

    /** 选中一个候选：类别写入字段并关闭（关联玩家的候选由 {@code PmChipInput} 自己处理）。 */
    private void applyOption(Option opt) {
        categoryId = opt.id();
        closePopup();
    }

    // ----------------------------------------------------------------
    // 输入
    // ----------------------------------------------------------------

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        // 胶囊候选弹层优先（它画在最上面）：它接下的点击（候选行、滚动条、输入框本身）就地处理；
        // 它没接下的（面板其它地方）只收起候选，这次点击继续往下走
        // ——否则「弹层一出来，点别的输入框要点两次」。
        if (targetChip != null && targetChip.hasPopup()) {
            if (targetChip.mouseClicked(mx, my, button)) {
                setFocused(targetChip); // 选中候选后继续在胶囊框里打字（否则会打到内容框里）
                return true;
            }
        }
        if (openPopup != Popup.NONE) {
            return handlePopupClick(mx, my);
        }

        int[] cancel = cancelRect();
        if (PmButton.hit(mx, my, cancel[0], cancel[1], cancel[2], cancel[3])) {
            onClose();
            return true;
        }

        if (!submitted) {
            int[] submit = submitRect();
            if (!awaiting && PmButton.hit(mx, my, submit[0], submit[1], submit[2], submit[3])) {
                submit();
                return true;
            }
            int[] category = categoryRect();
            if (PmButton.hit(mx, my, category[0], category[1], category[2], category[3])) {
                openPopup(Popup.CATEGORY, category);
                return true;
            }
        }

        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        if (PmScrollbar.isDragging()) {
            int next = PmScrollbar.dragV(SCROLL_ID_POPUP, my);
            if (next >= 0) popupScroll = next;
            return true;
        }
        if (targetChip != null && targetChip.mouseDragged(mx, my, button, dx, dy)) return true;
        return super.mouseDragged(mx, my, button, dx, dy);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        if (targetChip != null) targetChip.mouseReleased(mx, my, button);
        PmScrollbar.endDrag();
        return super.mouseReleased(mx, my, button);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        if (targetChip != null && targetChip.hasPopup() && targetChip.mouseScrolled(mx, my, delta)) return true;
        if (openPopup != Popup.NONE) {
            List<Option> items = popupOptions();
            int[] box = popupBox();
            if (box.length > 0) {
                int innerTop = box[1] + 2;
                int innerBottom = box[3] - 2;
                int visible = Math.max(1, (innerBottom - innerTop) / ROW_H);
                int maxOffset = Math.max(0, items.size() - visible);
                int step = delta > 0 ? -1 : 1;
                popupScroll = Math.max(0, Math.min(maxOffset, popupScroll + step));
            }
            return true; // 弹层打开时吞掉滚轮，不让下层跟着滚
        }
        return super.mouseScrolled(mx, my, delta);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // 胶囊候选弹层优先：Esc 只关弹层（不关整个面板）、上下选择、回车/Tab 确认。
        // 必须在这里拦：原版 Screen 的 Esc 是「关界面」，放给 super 就直接把面板关了。
        if (targetChip != null && targetChip.hasPopup()) {
            if (targetChip.keyPressed(keyCode, scanCode, modifiers)) return true;
        }
        // 弹层优先：上下选择、回车/Tab 确认、Esc 只关弹层而不关整个面板
        if (openPopup != Popup.NONE) {
            List<Option> items = popupOptions();
            switch (keyCode) {
                case KEY_ESC -> {
                    closePopup();
                    return true;
                }
                case KEY_UP -> {
                    movePopupSelection(items, -1);
                    return true;
                }
                case KEY_DOWN -> {
                    movePopupSelection(items, 1);
                    return true;
                }
                case KEY_ENTER, KEY_TAB -> {
                    if (!items.isEmpty()) {
                        applyOption(items.get(Math.max(0, Math.min(popupIndex, items.size() - 1))));
                    } else {
                        closePopup();
                    }
                    return true;
                }
                default -> {
                    // 其它按键放行给输入框：继续打字时补全列表会实时刷新
                }
            }
        }
        if (awaiting && keyCode == KEY_ESC) {
            return true; // 等待回包时不允许关面板，否则回包无处显示
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    private void movePopupSelection(List<Option> items, int dir) {
        if (items.isEmpty()) return;
        int[] box = popupBox();
        int innerTop = box.length > 0 ? box[1] + 2 : 0;
        int innerBottom = box.length > 0 ? box[3] - 2 : 0;
        int visible = Math.max(1, (innerBottom - innerTop) / ROW_H);
        popupIndex = Math.max(0, Math.min(items.size() - 1, popupIndex + dir));
        if (popupIndex < popupScroll) {
            popupScroll = popupIndex;
        } else if (popupIndex >= popupScroll + visible) {
            popupScroll = popupIndex - visible + 1;
        }
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        if (awaiting) return true;
        // 打字统一交给获得焦点的控件（原版 ContainerEventHandler 只把 charTyped 发给 getFocused()）：
        // 胶囊框自己决定收不收这个字符，这里不再插手
        boolean handled = super.charTyped(codePoint, modifiers);
        // 可见字符却没被收下：要么焦点不在任何输入框上，要么当前控件按规则拒绝了它
        // （例如胶囊已满、字符是格式符）。这一行是「框点得亮但打不进字」在整合包里唯一能自查的东西，
        // 所以每次打开面板只报一次、但要把「字符 + 焦点是谁」都报全。
        if (!handled && !warnedUnhandledChar && codePoint >= ' ' && codePoint != 127) {
            warnedUnhandledChar = true;
            GuiEventListener focused = getFocused();
            LOGGER.warn(
                    "[CCNR-PM] 举报面板收到了字符但没有任何输入框接收它：字符='{}'({})，当前焦点={}（焦点为 null = 界面里没人持有焦点；若焦点是本模组的输入框，则是它按规则拒绝了这个字符）",
                    codePoint,
                    (int) codePoint,
                    focused == null ? "null" : focused.getClass().getName());
        }
        return handled;
    }

    @Override
    public void onClose() {
        open = null;
        PmScrollbar.endDrag();
        Minecraft.getInstance().setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // ----------------------------------------------------------------
    // 提交与回包
    // ----------------------------------------------------------------

    /** 本地预校验（与服务端同一套规则）→ 通过则发包。 */
    private void submit() {
        if (awaiting || submitted) return;
        errors = List.of();

        ReportDraft draft = new ReportDraft(
                targetChip == null ? "" : targetChip.value(),
                categoryId,
                contentBox == null ? "" : contentBox.getValue(),
                detailBox == null ? "" : detailBox.getValue());

        List<ReportValidator.Error> local = ReportValidator.validate(
                draft, new CategoryRegistry(categories), new ReportValidator.Limits(1, maxContent, maxDetail));

        if (!local.isEmpty()) {
            errors = local.stream().map(e -> renderError(e.key(), e.args())).toList();
            return;
        }

        awaiting = true;
        if (targetChip != null) targetChip.setEditable(false);
        closePopup();
        PmChannel.sendToServer(
                new PmPackets.SubmitTicketC2S(draft.targetRaw(), draft.categoryId(), draft.content(), draft.detail()));
    }

    /** 服务端回包。 */
    public void onResult(boolean ok, String label, String errorsJson) {
        awaiting = false;
        if (ok) {
            submitted = true;
            resultLabel = label;
            errors = List.of();
            // 已建单：锁住表单，只留「关闭」按钮，避免重复提交
            if (contentBox != null) contentBox.setEditable(false);
            if (targetChip != null) targetChip.setEditable(false);
            if (detailBox != null) detailBox.setEditable(false);
            closePopup();
            return;
        }
        if (targetChip != null) targetChip.setEditable(true);
        errors = parseErrors(errorsJson);
        if (errors.isEmpty()) {
            errors = List.of(Component.translatable("ccnr_pm.report.err.not_ready"));
        }
    }

    private static List<Component> parseErrors(String json) {
        List<Component> out = new ArrayList<>();
        try {
            JsonElement el = JsonUtil.GSON.fromJson(json, JsonElement.class);
            if (el == null || !el.isJsonArray()) return out;
            JsonArray arr = el.getAsJsonArray();
            for (JsonElement e : arr) {
                if (!e.isJsonObject()) continue;
                JsonObject o = e.getAsJsonObject();
                String key = JsonUtil.str(o, "key", "");
                if (key.isBlank()) continue;
                List<String> args = new ArrayList<>();
                if (o.has("args") && o.get("args").isJsonArray()) {
                    for (JsonElement a : o.getAsJsonArray("args")) args.add(a.getAsString());
                }
                out.add(renderError(key, args));
            }
        } catch (Exception ignored) {
            // 解析失败时返回空表，由调用方兜底成通用错误提示
        }
        return out;
    }

    /** 错误语言键 → 可读文本；语言包缺键时退化为键名，避免显示成空白。 */
    private static Component renderError(String key, List<String> args) {
        return args.isEmpty() ? Component.translatable(key) : Component.translatable(key, args.toArray(new Object[0]));
    }

    /** 类别显示名（按 id）：走共享入口，避免与 HUD 卡片各写一套回退逻辑。 */
    private String categoryLabel(String id) {
        return com.ccnrcom.pm.client.ui.PmNames.categoryName(id);
    }

    private String categoryLabel(TicketCategory c) {
        if (c.hasCustomName()) return c.customName();
        String key = c.nameKey();
        String s = Component.translatable(key).getString();
        // 语言包缺键时 Minecraft 会把键原样返回；此时显示 id 比显示一串红色键名有用
        return s.equals(key) ? c.id() : s;
    }

    private static String tr(String key, Object... args) {
        return args.length == 0
                ? Component.translatable(key).getString()
                : Component.translatable(key, args).getString();
    }
}
