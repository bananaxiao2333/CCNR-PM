/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.pm.client.hud;

import com.ccnrcom.pm.client.ui.PmNames;
import com.ccnrcom.pm.client.ui.PmTheme;
import com.ccnrcom.pm.ticket.TicketBoard;
import com.ccnrcom.pm.ticket.TicketStatus;
import com.mojang.authlib.GameProfile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.client.gui.overlay.ForgeGui;
import net.minecraftforge.client.gui.overlay.IGuiOverlay;

/**
 * 左上角的**常驻工单看板**（HUD 浮层，不是 Screen）。
 *
 * <h2>它是什么</h2>
 * 管理员在线时，左上角持续显示当前**所有活跃工单**（待处理 / 处理中）各一张小卡片：
 *
 * <pre>
 * ┌───────────────────────┐  144 × 81（16:9 横版）
 * │▌作弊 / 外挂            │  ← 标题（类别）
 * │ 他隔着墙把我矿挖了，还…   │  ← 举报内容（2 行，超出截断补 …）
 * │ ▣│▢▢▢                 │  ← 头像贴底；竖线分隔「提交者 | 关联玩家」
 * └───────────────────────┘
 * </pre>
 *
 * <p>**卡片上不放工单编号**：编号对处理没有意义，管理员扫看板要的是
 * 「什么事、谁报的」。编号保留在管理面板里（那里要靠它引用工单、执行动作）。
 * 状态由**左缘强调条**表达（处理中＝亮色，待处理＝状态色），不占文字行。
 *
 * <h2>为什么不再自动消失</h2>
 * 早期版本把它当「一次性通知」，十几秒后淡出。这与需求「持续展示所有活跃未受理工单」
 * 直接冲突——管理员低头挖一会儿矿，工单就从视野里没了。
 * 现在的规则是：**卡片什么时候消失由工单状态决定**（办结/驳回后服务端不再把它算作活跃），
 * 不由时间决定。
 *
 * <h2>为什么内容只有这些</h2>
 * 职责是「抬头一眼看到有几张工单、谁报的、涉及谁」。正文与描述在管理面板里读
 * （{@code PmAdminScreen}），不在浮层上堆——浮层每多一行就多挡一块游戏视野。
 *
 * <h2>边界</h2>
 * <ul>
 *   <li>**浮层无法点击**：无界面时鼠标被相机抓取、没有光标坐标。处理动作在管理面板里做。</li>
 *   <li>HUD 浮层只在**没有打开任何界面**时绘制（原版行为）；开着背包时看不到，但关掉就回来。</li>
 *   <li>头像优先用服务端下发的 base64（第三方/离线服务器的正解），拿不到回退到客户端解析。</li>
 * </ul>
 */
public final class TicketNoticeOverlay implements IGuiOverlay {

    /** 当前看板（服务端整份下发的权威数据）。只在客户端主线程读写。 */
    private static volatile TicketBoard board = TicketBoard.empty();

    /** 皮肤解析缓存：键为 uuid（无 uuid 时用名字）。 */
    private static final Map<String, ResourceLocation> SKIN_CACHE = new HashMap<>();

    /** 用服务端下发的整份看板替换本地数据。 */
    public static void accept(String json) {
        board = TicketBoard.fromJson(json);
    }

    /** 清空并释放 GPU 贴图（离开世界时调用）。 */
    public static void clear() {
        board = TicketBoard.empty();
        SKIN_CACHE.clear();
        AvatarTextures.releaseAll();
    }

    public static boolean isEmpty() {
        return board.isEmpty();
    }

    /** 当前看板（交互屏与只读浮层共用同一份数据，切过去时卡片不会跳位）。 */
    public static TicketBoard board() {
        return board;
    }

    /** 排序后的卡片：**已认领的置顶**（管理员认领过的最该被看见）。 */
    public static List<TicketBoard.Card> orderedCards() {
        return ordered(board.cards());
    }

    public static int size() {
        return board.size();
    }

    @Override
    public void render(ForgeGui gui, GuiGraphics g, float partialTick, int screenWidth, int screenHeight) {
        // 交互看板（按住 P 打开的那个）打开时**不画本浮层**：原版 HUD 是连 Screen 一起渲染在底层的，
        // 于是只读卡片与可点卡片会画在同一位置、叠成重影（用户实机反馈的「重叠」）。
        // 看板的可见性交给 PmBoardScreen 自己负责，关掉它的那一刻本浮层自然回来。
        if (Minecraft.getInstance().screen instanceof com.ccnrcom.pm.client.PmBoardScreen) return;

        TicketBoard current = board;
        if (current.isEmpty()) {
            if (!SKIN_CACHE.isEmpty()) SKIN_CACHE.clear();
            return;
        }

        // 已认领的置顶（用户要求「认领的自动置顶」），同组内按时间倒序（服务端已排好序）
        List<TicketBoard.Card> ordered = ordered(current.cards());

        int drawn = 0;
        for (TicketBoard.Card card : ordered) {
            if (drawn >= NoticeCardLayout.MAX_VISIBLE) break;
            renderCard(g, card, NoticeCardLayout.MARGIN, NoticeCardLayout.cardY(drawn));
            drawn++;
        }

        // 过多的折叠成「剩余 N 张」提示，而不是继续往下堆满屏幕
        int remaining = ordered.size() - drawn;
        if (remaining > 0) {
            renderOverflowHint(g, drawn, remaining);
        }
    }

    /**
     * 排序：**处理中优先**（管理员认领过的最该被看见），其次按服务端给的顺序（时间倒序）。
     *
     * <p>用稳定排序，因此同组内保持服务端的顺序，不会每次刷新都跳位。
     */
    public static List<TicketBoard.Card> ordered(List<TicketBoard.Card> cards) {
        List<TicketBoard.Card> out = new ArrayList<>(cards);
        out.sort((a, b) -> Integer.compare(rank(a), rank(b)));
        return out;
    }

    private static int rank(TicketBoard.Card c) {
        return TicketStatus.CLAIMED.id().equals(c.status()) ? 0 : 1;
    }

    /** 「还有 N 张」提示：紧挨在最后一张卡片下方，同样是靠边的小条。 */
    private void renderOverflowHint(GuiGraphics g, int drawn, int remaining) {
        Font font = Minecraft.getInstance().font;
        int x = NoticeCardLayout.MARGIN;
        int y = NoticeCardLayout.cardY(drawn);
        String text = tr("ccnr_pm.board.more", Integer.toString(remaining));
        int w = font.width(text) + 10;
        int h = 12;
        g.fill(x, y, x + w, y + h, PmTheme.OVERLAY);
        PmTheme.outlined(g, x, y, x + w, y + h, PmTheme.PANEL_BORDER);
        g.fill(x, y, x + 3, y + h, PmTheme.CYAN_DIM);
        g.drawString(font, text, x + 6, y + 2, PmTheme.TEXT_SECONDARY, false);
    }

    /**
     * 画一张卡片（只读版式）。内容**靠边对齐**：标题行在顶、头像行在底。
     */
    private void renderCard(GuiGraphics g, TicketBoard.Card card, int px1, int py1) {
        Font font = Minecraft.getInstance().font;
        int px2 = px1 + NoticeCardLayout.CARD_W;
        int py2 = py1 + NoticeCardLayout.CARD_H;

        g.fill(px1, py1, px2, py2, PmTheme.OVERLAY);
        PmTheme.outlined(g, px1, py1, px2, py2, PmTheme.PANEL_BORDER_BRIGHT);
        g.fill(px1 + 1, py1 + 1, px2 - 1, py1 + 2, PmTheme.alphaBlend(PmTheme.PANEL_BORDER_BRIGHT, 0x2E));
        boolean claimed = TicketStatus.CLAIMED.id().equals(card.status());
        g.fill(px1, py1, px1 + 3, py2, claimed ? PmTheme.CYAN : PmTheme.statusColor(card.status()));

        int innerX1 = px1 + NoticeCardLayout.PAD;
        int innerX2 = px2 - NoticeCardLayout.PAD;

        // 标题行：左＝举报内容，右＝类别 + **带色的状态标签**（浮层与交互看板共用同一套画法）
        renderHeaderRow(g, card, px1, py1);

        // 详细描述：按像素断行，放不下时末行截断补省略号
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

        // 头像行（含每个头像上方的小名字）
        renderAvatarRow(g, card, px1, py1);
    }

    /**
     * 画标题行：**左**＝举报内容，**右**＝类别 + **带状态的标签**。
     *
     * <h2>为什么收成一个共享入口</h2>
     * 只读浮层与交互看板各画一份的话，改一次版式就得改两处，漏一处会出现
     * 「同一张工单在两个界面里长得不一样」。这里由 {@code PmBoardScreen} 直接调用。
     *
     * <h2>宽度分配顺序（用户要求「状态文字要能分辨」）</h2>
     * **状态 → 标题（保底 {@link NoticeCardLayout#MIN_TITLE_W}）→ 类别**：
     * 状态标签必须完整可读（它就是要被分辨的那个东西），标题不能被挤成看不懂的几个字，
     * 类别可以让位——它在工单列表与详情里都还有一份。
     *
     * <p>状态用 {@link PmTheme#statusColor} 上色：待处理＝告警红、处理中＝亮、已办结＝灰蓝、已驳回＝灰。
     */
    public static void renderHeaderRow(GuiGraphics g, TicketBoard.Card card, int px1, int py1) {
        Font font = Minecraft.getInstance().font;
        int innerX1 = px1 + NoticeCardLayout.PAD;
        int innerX2 = px1 + NoticeCardLayout.CARD_W - NoticeCardLayout.PAD;
        int y = py1 + NoticeCardLayout.titleY();

        String status = PmNames.statusName(card.status());
        int statusW = NoticeCardLayout.statusWidth(font.width(status));
        String category = PmNames.categoryName(card.category());
        int categoryW = NoticeCardLayout.categoryWidthFor(statusW, font.width(category));
        int titleW = NoticeCardLayout.titleWidthFor(statusW, categoryW);

        // 标题（举报内容）
        g.drawString(font, PmTheme.clip(font, card.content(), titleW), innerX1, y, PmTheme.TEXT_PRIMARY, false);

        // 类别：紧贴状态标签左侧，空间不够时被裁的是它
        if (categoryW > 0) {
            String cat = PmTheme.clip(font, category, categoryW);
            g.drawString(
                    font,
                    cat,
                    px1 + NoticeCardLayout.categoryRight(statusW) - font.width(cat),
                    y,
                    PmTheme.TEXT_SECONDARY,
                    false);
        }

        // 状态标签：右上角，状态色（最优先保证完整显示）
        String tag = PmTheme.clip(font, status, statusW);
        g.drawString(font, tag, innerX2 - font.width(tag), y, PmTheme.statusColor(card.status()), false);
    }

    /**
     * 画「小名字 + 头像」两行，提交者与关联玩家用竖线分隔。
     *
     * <p>**公开给交互看板复用**（{@code PmBoardScreen} 在另一个包）：两处各画一份的话，
     * 头像位置与名字对齐迟早会不一致——这正是「同类构件只能走共享入口」的由来。
     *
     * <p>坐标**一律来自 {@link BoardAvatarLayout}**：交互看板要靠同一套坐标做命中判定，
     * 若这里自己算一份，就会出现「点中的头像和弹出的菜单不是同一个人」。
     */
    public static void renderAvatarRow(GuiGraphics g, TicketBoard.Card card, int px1, int py1) {
        Font font = Minecraft.getInstance().font;
        int innerX2 = px1 + NoticeCardLayout.CARD_W - NoticeCardLayout.PAD;
        int rowY = py1 + NoticeCardLayout.avatarRowY();
        int nameY = py1 + NoticeCardLayout.nameY();

        List<BoardAvatarLayout.Slot> slots = BoardAvatarLayout.slots(card, px1, py1);

        // 竖线只在「提交者与关联玩家都存在」时画：否则它分隔的是一个空栏
        if (!card.reporter().isBlank() && !card.targets().isEmpty()) {
            int dividerX =
                    px1 + BoardAvatarLayout.reporterHeadX() + NoticeCardLayout.HEAD + NoticeCardLayout.DIVIDER_GAP;
            g.fill(dividerX, nameY, dividerX + 1, rowY + NoticeCardLayout.HEAD, PmTheme.PANEL_BORDER_BRIGHT);
        }

        for (BoardAvatarLayout.Slot slot : slots) {
            String label = PmTheme.clip(font, slot.name(), NoticeCardLayout.HEAD);
            g.drawString(
                    font,
                    label,
                    slot.x() + (NoticeCardLayout.HEAD - font.width(label)) / 2,
                    nameY,
                    PmTheme.TEXT_DIM,
                    false);
            drawHead(g, slot.uuidOrNull(), slot.name(), card.avatars(), slot.x(), rowY, slot.submitter());
        }

        int total = card.targets().size();
        if (NoticeCardLayout.overflows(total)) {
            int toDraw = NoticeCardLayout.relatedToDraw(total);
            String more = "+" + (total - toDraw);
            int x = px1 + BoardAvatarLayout.targetHeadX(toDraw);
            if (innerX2 - x >= font.width(more)) {
                g.drawString(font, more, x + 1, rowY + (NoticeCardLayout.HEAD - 8) / 2, PmTheme.TEXT_SECONDARY, false);
            }
        }
    }

    /**
     * 画一个玩家头像。
     *
     * <p>贴图来源优先级：服务端下发的 base64 皮肤 → 客户端本地解析的皮肤 → 空槽。
     *
     * @param submitter 提交者亮框、关联玩家暗框。框画在头像**内侧**：
     *     外框在「无间隔」排版下会与邻居的框叠成两像素粗线
     */
    private static void drawHead(
            GuiGraphics g, UUID uuid, String name, Map<String, String> avatars, int x, int y, boolean submitter) {
        int frame = submitter ? PmTheme.CYAN : PmTheme.PANEL_BORDER;

        ResourceLocation tex = null;
        int texW = 64;
        int texH = 64;

        String b64 = uuid == null ? null : avatars.get(uuid.toString());
        if (b64 != null && !b64.isBlank()) {
            String cacheKey = (uuid == null ? name : uuid.toString()) + "_" + Integer.toHexString(b64.hashCode());
            AvatarTextures.Entry entry = AvatarTextures.get(cacheKey, b64);
            if (entry != null) {
                tex = entry.location();
                texW = entry.width();
                texH = entry.height();
            }
        }
        if (tex == null) tex = skinFor(uuid, name);

        if (tex == null) {
            g.fill(x, y, x + NoticeCardLayout.HEAD, y + NoticeCardLayout.HEAD, PmTheme.SURFACE_SUNKEN);
        } else {
            g.pose().pushPose();
            g.pose().translate(x, y, 0);
            float scale = NoticeCardLayout.HEAD / 8f;
            g.pose().scale(scale, scale, 1f);
            // 脸（8,8 起 8×8）与帽子层（40,8 起 8×8）；1.20.1 的 UV 是 float。
            // 贴图高必须用真实值，否则 64×32 的旧皮肤会画歪
            g.blit(tex, 0, 0, 8f, 8f, 8, 8, texW, texH);
            g.blit(tex, 0, 0, 40f, 8f, 8, 8, texW, texH);
            g.pose().popPose();
        }

        PmTheme.outlined(g, x, y, x + NoticeCardLayout.HEAD, y + NoticeCardLayout.HEAD, frame);
    }

    /**
     * 取玩家皮肤（服务端没给 base64 时的回退）。
     *
     * <p>优先用世界里那个实体的皮肤；拿不到就按 UUID 回退默认皮肤。
     * 1.20.1 没有 {@code PlayerSkin} 类（1.20.2 才有），所以走
     * {@code SkinManager.getInsecureSkinLocation}。结果按玩家缓存：看板每帧都在画。
     */
    private static ResourceLocation skinFor(UUID uuid, String name) {
        String key = uuid != null ? uuid.toString() : "name:" + name;
        ResourceLocation cached = SKIN_CACHE.get(key);
        if (cached != null) return cached;

        ResourceLocation tex = null;
        ClientLevel level = Minecraft.getInstance().level;
        if (level != null && uuid != null) {
            for (AbstractClientPlayer p : level.players()) {
                if (p.getUUID().equals(uuid)) {
                    tex = p.getSkinTextureLocation();
                    break;
                }
            }
        }
        if (tex == null) {
            UUID id = uuid != null
                    ? uuid
                    : UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
            try {
                tex = Minecraft.getInstance().getSkinManager().getInsecureSkinLocation(new GameProfile(id, name));
            } catch (Exception e) {
                tex = null;
            }
        }
        if (tex != null) SKIN_CACHE.put(key, tex);
        return tex;
    }

    private static String tr(String key, Object... args) {
        return args.length == 0
                ? Component.translatable(key).getString()
                : Component.translatable(key, args).getString();
    }
}
