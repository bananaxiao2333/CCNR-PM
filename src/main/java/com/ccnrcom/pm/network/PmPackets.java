/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.pm.network;

import com.ccnrcom.pm.ticket.ReportValidator;
import com.ccnrcom.pm.ticket.TicketCategory;
import com.ccnrcom.pm.ticket.TicketService;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

/**
 * 全部 C2S / S2C 包。
 *
 * <p>约定（沿用 CCNR 系列）：消息类为 {@code public static final class} 嵌在本类内；
 * 两个构造器（业务入参 / {@link FriendlyByteBuf} 解码）；实例方法 {@code encode}；静态 {@code handle}。
 *
 * <p>**长度上限必须与解码端严格一致**：{@code writeUtf}/{\@code readUtf} 的上限不匹配会造成缓冲区错位，
 * 后续所有字段全部读歪（且不会立刻报错，表现为「玩家名变成乱码」这类诡异现象）。 所有上限以常量形式声明一次，
 * 编解码共用，杜绝对不上。
 *
 * <p>包体只承载**数据**，不承载「服务端已经相信的结论」。{@code SubmitTicketC2S} 里的名字/类别
 * 都只是玩家的意图，服务端在 {@link TicketService#submit} 里全部重新校验。
 */
public final class PmPackets {

    // ---- 字段上限（编解码共用，改这里即可）----
    private static final int MAX_PREFILL = 4096;
    private static final int MAX_ROSTER = 1024;
    /** 通知包里携带的关联玩家数量上限（与实际规则解耦，只是防止载荷失控）。 */
    private static final int MAX_TARGETS_WIRE = 32;
    /**
     * 通知包里单个头像 base64 的长度上限。
     *
     * <p>超限的头像会被**丢弃而不是截断**——截断的 base64 解出来是坏图，客户端只会画出一个花屏方块，
     * 那比干脆不带头像更糟（客户端会回退到本地解析的皮肤）。
     */
    public static final int MAX_AVATAR_B64 = 32_768;
    /**
     * 面板一页 JSON 的上限。
     *
     * <p>一页默认 10 条，每条含正文（≤ maxContent）与描述（≤ maxDetail），
     * 最坏情况约 8KB；给到 128KB 是留足余量，同时仍能挡住畸形载荷。
     */
    private static final int MAX_PAGE_JSON = 131_072;

    private static final int MAX_CATEGORIES = 128;
    private static final int MAX_UUID = 36;
    private static final int MAX_NAME = 32;
    private static final int MAX_ID = 32;
    private static final int MAX_CUSTOM_NAME = 64;
    /**
     * 关联玩家原始输入上限：上限 8 个名字 × (16 字符 + 分隔符)，留足余量。
     *
     * <p>公开是为了让客户端的输入框直接把 {@code maxLength} 设成这个值——
     * 客户端允许输入的长度若超过编码上限，{@code writeUtf} 会在发包时抛异常，
     * 表现为「点提交没反应」。两者共用一个常量就不会出现这种失配。
     */
    public static final int MAX_TARGET_RAW = 192;

    private static final int MAX_CONTENT = 4096;
    private static final int MAX_DETAIL = 8192;
    private static final int MAX_ERRORS_JSON = 4096;

    private PmPackets() {}

    // ------------------------------------------------------------------
    // S2C：打开举报工单面板
    // ------------------------------------------------------------------

    /**
     * 打开工单面板（S2C）。
     *
     * <p>由两处触发：非管理员执行 {@code /a <消息>}（{@code prefill} = 那条消息），
     * 以及 {@code /pm report} 手动打开（{@code prefill} = 空串）。
     *
     * <p>随包下发在线玩家名单与可选类别，是为了让面板**打开即可用**：不需要客户端再发一次请求，
     * 也就不存在「面板已显示但选项还在加载」的中间态。名单会在提交时被服务端重新核对， 因此这里的快照过期不会造成错误受理。
     */
    public static final class OpenReportScreenS2C {
        public final String prefill;
        public final List<TicketService.OnlinePlayer> roster;
        public final List<TicketCategory> categories;
        /** 服务端长度上限；下发是为了让输入框 maxLength 与本地预校验和服务端完全一致。 */
        public final int maxContent;

        public final int maxDetail;

        public OpenReportScreenS2C(
                String prefill,
                List<TicketService.OnlinePlayer> roster,
                List<TicketCategory> categories,
                int maxContent,
                int maxDetail) {
            this.prefill = prefill == null ? "" : prefill;
            this.roster = roster == null ? List.of() : List.copyOf(roster);
            this.categories = categories == null ? List.of() : List.copyOf(categories);
            this.maxContent = maxContent;
            this.maxDetail = maxDetail;
        }

        public OpenReportScreenS2C(FriendlyByteBuf buf) {
            this.prefill = buf.readUtf(MAX_PREFILL);
            int n = Math.min(buf.readVarInt(), MAX_ROSTER);
            List<TicketService.OnlinePlayer> r = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                r.add(new TicketService.OnlinePlayer(buf.readUtf(MAX_UUID), buf.readUtf(MAX_NAME)));
            }
            this.roster = List.copyOf(r);
            int c = Math.min(buf.readVarInt(), MAX_CATEGORIES);
            List<TicketCategory> cats = new ArrayList<>(c);
            for (int i = 0; i < c; i++) {
                String id = buf.readUtf(MAX_ID);
                String custom = buf.readUtf(MAX_CUSTOM_NAME);
                int order = buf.readVarInt();
                boolean enabled = buf.readBoolean();
                cats.add(new TicketCategory(id, custom.isEmpty() ? null : custom, order, enabled));
            }
            this.categories = List.copyOf(cats);
            this.maxContent = buf.readVarInt();
            this.maxDetail = buf.readVarInt();
        }

        public void encode(FriendlyByteBuf buf) {
            buf.writeUtf(prefill, MAX_PREFILL);
            buf.writeVarInt(Math.min(roster.size(), MAX_ROSTER));
            for (int i = 0; i < Math.min(roster.size(), MAX_ROSTER); i++) {
                TicketService.OnlinePlayer p = roster.get(i);
                buf.writeUtf(p.uuid() == null ? "" : p.uuid(), MAX_UUID);
                buf.writeUtf(p.name() == null ? "" : p.name(), MAX_NAME);
            }
            buf.writeVarInt(Math.min(categories.size(), MAX_CATEGORIES));
            for (int i = 0; i < Math.min(categories.size(), MAX_CATEGORIES); i++) {
                TicketCategory t = categories.get(i);
                buf.writeUtf(t.id(), MAX_ID);
                buf.writeUtf(t.hasCustomName() ? t.customName() : "", MAX_CUSTOM_NAME);
                buf.writeVarInt(t.order());
                buf.writeBoolean(t.enabled());
            }
            buf.writeVarInt(maxContent);
            buf.writeVarInt(maxDetail);
        }

        public static void handle(OpenReportScreenS2C msg, Supplier<NetworkEvent.Context> ctx) {
            net.minecraftforge.fml.DistExecutor.unsafeRunWhenOn(
                    net.minecraftforge.api.distmarker.Dist.CLIENT,
                    () -> () -> com.ccnrcom.pm.client.PmClientPacketHandler.onOpenReportScreen(msg));
        }
    }

    // ------------------------------------------------------------------
    // C2S：提交工单
    // ------------------------------------------------------------------

    /**
     * 提交工单（C2S）。全部字段都是「意图」，服务端逐项重校验。
     *
     * <p>{@code targetRaw} 是**未拆分的原始输入**（如 {@code "Bob, Carol"}）：拆分规则属于校验，
     * 放在服务端用 {@link ReportValidator#parseTargets} 做一次即可，客户端调同一份实现做预览。
     * 若这里传已拆好的列表，两端就会各写一套拆分逻辑，迟早会在「中文逗号算不算分隔符」上分歧。
     */
    public static final class SubmitTicketC2S {
        public final String targetRaw;
        public final String categoryId;
        public final String content;
        public final String detail;

        public SubmitTicketC2S(String targetRaw, String categoryId, String content, String detail) {
            this.targetRaw = targetRaw == null ? "" : targetRaw;
            this.categoryId = categoryId == null ? "" : categoryId;
            this.content = content == null ? "" : content;
            this.detail = detail == null ? "" : detail;
        }

        public SubmitTicketC2S(FriendlyByteBuf buf) {
            this(buf.readUtf(MAX_TARGET_RAW), buf.readUtf(MAX_ID), buf.readUtf(MAX_CONTENT), buf.readUtf(MAX_DETAIL));
        }

        public void encode(FriendlyByteBuf buf) {
            buf.writeUtf(targetRaw, MAX_TARGET_RAW);
            buf.writeUtf(categoryId, MAX_ID);
            buf.writeUtf(content, MAX_CONTENT);
            buf.writeUtf(detail, MAX_DETAIL);
        }

        public static void handle(SubmitTicketC2S msg, Supplier<NetworkEvent.Context> ctx) {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) return;
            com.ccnrcom.pm.network.PmServerHandlers.onSubmitTicket(
                    player,
                    new com.ccnrcom.pm.ticket.ReportDraft(msg.targetRaw, msg.categoryId, msg.content, msg.detail));
        }
    }

    // ------------------------------------------------------------------
    // S2C：提交结果
    // ------------------------------------------------------------------

    // ------------------------------------------------------------------
    // S2C：提交结果
    // ------------------------------------------------------------------

    /**
     * 提交结果（S2C）。
     *
     * <p>成功时带工单短号（客户端据此显示「已建单 #a3f2c1d8」并关闭面板）；
     * 失败时带错误列表，客户端用**本地语言包**渲染成可读文字。
     *
     * <p>为什么传 key + args 而不是渲染好的字符串：服务端的语言由服务端决定，
     * 中文客户端应当看到中文提示。把 key 交给客户端渲染是唯一正确的做法。
     */
    public static final class SubmitResultS2C {
        public final boolean ok;
        public final String label;
        public final String errorsJson;

        public SubmitResultS2C(boolean ok, String label, String errorsJson) {
            this.ok = ok;
            this.label = label == null ? "" : label;
            this.errorsJson = errorsJson == null ? "[]" : errorsJson;
        }

        public SubmitResultS2C(FriendlyByteBuf buf) {
            this(buf.readBoolean(), buf.readUtf(16), buf.readUtf(MAX_ERRORS_JSON));
        }

        public void encode(FriendlyByteBuf buf) {
            buf.writeBoolean(ok);
            buf.writeUtf(label, 16);
            buf.writeUtf(errorsJson, MAX_ERRORS_JSON);
        }

        /** 由校验错误构造（成功时用 {@link #success}）。 */
        public static SubmitResultS2C of(List<ReportValidator.Error> errors) {
            com.google.gson.JsonArray arr = new com.google.gson.JsonArray();
            for (ReportValidator.Error e : errors) {
                com.google.gson.JsonObject o = new com.google.gson.JsonObject();
                o.addProperty("key", e.key());
                com.google.gson.JsonArray args = new com.google.gson.JsonArray();
                for (String a : e.args()) args.add(a);
                o.add("args", args);
                arr.add(o);
            }
            return new SubmitResultS2C(false, "", arr.toString());
        }

        public static SubmitResultS2C success(String label) {
            return new SubmitResultS2C(true, label, "[]");
        }

        public static void handle(SubmitResultS2C msg, Supplier<NetworkEvent.Context> ctx) {
            net.minecraftforge.fml.DistExecutor.unsafeRunWhenOn(
                    net.minecraftforge.api.distmarker.Dist.CLIENT,
                    () -> () -> com.ccnrcom.pm.client.PmClientPacketHandler.onSubmitResult(msg));
        }
    }

    // ------------------------------------------------------------------
    // S2C：左上角常驻看板
    // ------------------------------------------------------------------

    /**
     * 活跃工单看板（S2C，只发给有工单管理权限的在线管理员）。
     *
     * <p>**常驻**：客户端收到后持续显示，不再像早期版本那样十几秒后淡出——
     * 那与「持续展示所有活跃未受理工单」的需求直接冲突。
     *
     * <p>**整份替换**：每次推一份完整的活跃列表，客户端整体替换本地看板。
     * 增量会让客户端需要维护合并逻辑，而「哪张工单还算活跃」只有服务端知道
     * （被认领、办结、驳回都会让它离开看板），整份替换不可能出现不同步。
     *
     * <p>载荷只含卡片真正会画的字段（短号/状态/类别/提交者/关联玩家 + 已缓存头像），
     * 不含正文与描述——那些只在管理面板里读，见 {@code TicketBoard} 的类注释。
     */
    public static final class TicketBoardS2C {
        public final String json;

        public TicketBoardS2C(String json) {
            this.json = json == null ? "{\"cards\":[]}" : json;
        }

        public TicketBoardS2C(FriendlyByteBuf buf) {
            this(buf.readUtf(MAX_PAGE_JSON));
        }

        public void encode(FriendlyByteBuf buf) {
            buf.writeUtf(json, MAX_PAGE_JSON);
        }

        public static void handle(TicketBoardS2C msg, Supplier<NetworkEvent.Context> ctx) {
            net.minecraftforge.fml.DistExecutor.unsafeRunWhenOn(
                    net.minecraftforge.api.distmarker.Dist.CLIENT,
                    () -> () -> com.ccnrcom.pm.client.PmClientPacketHandler.onTicketBoard(msg.json));
        }
    }

    // ------------------------------------------------------------------
    // 管理面板（C2S 请求 / 动作，S2C 数据）
    // ------------------------------------------------------------------

    /**
     * 请求一页工单数据（C2S）。
     *
     * <p>面板**打开时**与**每次动作后**都会发一次：与其让服务端做增量推送，
     * 不如每次拿一份权威快照——管理面板是低频操作，少一份"增量合并"的状态就少一类不一致。
     */
    public static final class RequestTicketsC2S {
        public final String filter;
        public final int page;

        public RequestTicketsC2S(String filter, int page) {
            this.filter = filter == null ? "all" : filter;
            this.page = page;
        }

        public RequestTicketsC2S(FriendlyByteBuf buf) {
            this(buf.readUtf(16), buf.readVarInt());
        }

        public void encode(FriendlyByteBuf buf) {
            buf.writeUtf(filter, 16);
            buf.writeVarInt(page);
        }

        public static void handle(RequestTicketsC2S msg, Supplier<NetworkEvent.Context> ctx) {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) return;
            PmServerHandlers.onRequestTickets(player, msg.filter, msg.page);
        }
    }

    /**
     * 一页工单数据（S2C）。
     *
     * <p>载荷是 JSON 字符串：面板是**结构会变**的管理数据（以后要加筛选、排序、字段），
     * 逐字段编解码会让每加一个字段都要动协议两端。编解码只有一份实现（{@code TicketPage}），
     * 由单测保证往返一致。
     */
    public static final class TicketListS2C {
        public final String json;

        public TicketListS2C(String json) {
            this.json = json == null ? "{}" : json;
        }

        public TicketListS2C(FriendlyByteBuf buf) {
            this(buf.readUtf(MAX_PAGE_JSON));
        }

        public void encode(FriendlyByteBuf buf) {
            buf.writeUtf(json, MAX_PAGE_JSON);
        }

        public static void handle(TicketListS2C msg, Supplier<NetworkEvent.Context> ctx) {
            net.minecraftforge.fml.DistExecutor.unsafeRunWhenOn(
                    net.minecraftforge.api.distmarker.Dist.CLIENT,
                    () -> () -> com.ccnrcom.pm.client.PmClientPacketHandler.onTicketList(msg.json));
        }
    }

    /**
     * 管理动作（C2S）：认领 / 办结 / 驳回。
     *
     * <p>只送「对哪张工单做什么」——**能不能做由服务端重新判定**（权限、状态机、目标存在性）。
     * 面板上的按钮禁用只是体验，不构成约束。
     */
    public static final class TicketActionC2S {
        public final String action;
        public final String ticketId;
        public final String note;

        public TicketActionC2S(String action, String ticketId, String note) {
            this.action = action == null ? "" : action;
            this.ticketId = ticketId == null ? "" : ticketId;
            this.note = note == null ? "" : note;
        }

        public TicketActionC2S(FriendlyByteBuf buf) {
            this(buf.readUtf(16), buf.readUtf(MAX_UUID), buf.readUtf(MAX_DETAIL));
        }

        public void encode(FriendlyByteBuf buf) {
            buf.writeUtf(action, 16);
            buf.writeUtf(ticketId, MAX_UUID);
            buf.writeUtf(note, MAX_DETAIL);
        }

        public static void handle(TicketActionC2S msg, Supplier<NetworkEvent.Context> ctx) {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) return;
            PmServerHandlers.onTicketAction(player, msg.action, msg.ticketId, msg.note);
        }
    }

    /**
     * 管理动作结果（S2C）。
     *
     * <p>成功时服务端会**紧接着**再推一份 {@link TicketListS2C}；
     * 这个包只负责告诉客户端「成功了没有、失败是什么原因」。
     * 两者分开是因为刷新失败不该让"操作成功"这个事实看起来也失败了。
     */
    public static final class AdminResultS2C {
        public final boolean ok;
        public final String messageKey;

        public AdminResultS2C(boolean ok, String messageKey) {
            this.ok = ok;
            this.messageKey = messageKey == null ? "" : messageKey;
        }

        public AdminResultS2C(FriendlyByteBuf buf) {
            this(buf.readBoolean(), buf.readUtf(128));
        }

        public void encode(FriendlyByteBuf buf) {
            buf.writeBoolean(ok);
            buf.writeUtf(messageKey, 128);
        }

        public static void handle(AdminResultS2C msg, Supplier<NetworkEvent.Context> ctx) {
            net.minecraftforge.fml.DistExecutor.unsafeRunWhenOn(
                    net.minecraftforge.api.distmarker.Dist.CLIENT,
                    () -> () -> com.ccnrcom.pm.client.PmClientPacketHandler.onAdminResult(msg.ok, msg.messageKey));
        }
    }

    /**
     * 打开管理面板（S2C，空载荷）。
     *
     * <p>为什么由服务端触发而不是客户端自己开：{@code /pm panel} 是服务端命令，
     * 只有服务端知道命令是否被授权通过。客户端收到这个包才开面板，
     * 因此「谁有权限」这个问题只有一个答案来源。
     *
     * <p>也正因为**服务端在发它之前已经用 {@code canAdminTicket} 判过权限**，
     * 客户端收到它就可以把自己的权限镜像置为 true——不必等 {@code AdminStateSync}
     * 那一秒的复查。这顺手关掉了一个真实缺陷的窗口：权限刚被授予、客户端的标志还是
     * 「已确认的 false」时，面板会被 {@code PmAdminScreen.tick()} 立刻关掉。
     */
    public static final class OpenAdminPanelS2C {
        public OpenAdminPanelS2C() {}

        public OpenAdminPanelS2C(FriendlyByteBuf buf) {}

        public void encode(FriendlyByteBuf buf) {}

        public static void handle(OpenAdminPanelS2C msg, Supplier<NetworkEvent.Context> ctx) {
            net.minecraftforge.fml.DistExecutor.unsafeRunWhenOn(
                    net.minecraftforge.api.distmarker.Dist.CLIENT,
                    () -> () -> com.ccnrcom.pm.client.PmClientPacketHandler.onOpenAdminPanel());
        }
    }

    // ------------------------------------------------------------------
    // 看板头像上的玩家动作
    // ------------------------------------------------------------------

    /**
     * 动作 id 常量。
     *
     * <p>**字符串两端共用这里的常量**：客户端与服务端各写一份字面量，改一处忘一处的结果就是
     * 「点了没反应」——而这恰恰是禁止起服务验证的项目里最难发现的一类缺陷。
     */
    public static final String ACT_TP_HERE = "tp_here";

    public static final String ACT_TP_TO = "tp_to";
    public static final String ACT_SPAWN = "spawn";
    public static final String ACT_SPAWN_TP = "spawn_tp";
    public static final String ACT_OBSERVER = "observer";

    // ------------------------------------------------------------------
    // 对局管理动作 id（同样是字符串协议，两端共用这些常量）
    // ------------------------------------------------------------------

    /**
     * 切到指定幕（{@code arg} = 幕 id）。
     *
     * <p><b>空 {@code arg} = 切下一幕</b>——这不是这里定的规则，而是 CCNR-RP 自己的
     * {@code switchPhase("")} 语义（它的 {@code /rp phase set} 也是这么用的）。
     * PM 侧只做透传，不另立一套「下一幕」的规则，否则对方改了切幕语义我们不会跟着改。
     */
    public static final String ACT_MATCH_PHASE = "match_phase";

    /** 强制触发某个事件（{@code arg} = 事件 id）。 */
    public static final String ACT_MATCH_EVENT_ON = "match_event_on";

    /** 强制结束某个正在运行的事件（{@code arg} = 事件 id）。 */
    public static final String ACT_MATCH_EVENT_OFF = "match_event_off";

    /**
     * 收束对局（{@code arg} 忽略）。
     *
     * <p>收束理由由**服务端常量**给出而不从这里传：CCNR-RP 把理由拼进上报载荷时用的是字符串拼接，
     * 客户端自由文本里的一个引号就能让存进数据库的 JSON 非法（见 {@code PmServerHandlers.MATCH_END_REASON}）。
     */
    public static final String ACT_MATCH_END = "match_end";

    /** 清空事件 = 重开一局（对方语义：换 match_id + 清上一局的事件流）。 */
    public static final String ACT_MATCH_RESET = "match_reset";

    /** 只播结束动画，不结算也不收束对局（与 {@link #ACT_MATCH_END} 刻意分开）。 */
    public static final String ACT_MATCH_GAME_OVER = "match_game_over";

    /** 玩家动作参数（职业 id）长度上限。 */
    private static final int MAX_ARG = 64;

    /**
     * 看板头像上的玩家动作（C2S）：把玩家传送过来 / 传送到该玩家 / 刷出 / 刷出并传送至此 / 送回阴间。
     *
     * <p>载荷里的 {@code targetUuid} / {@code targetName} **不是身份字段**——身份永远只有连接
     * ({@code ctx.getSender()}) 里那一个。它们是「要对谁动手」的目标描述，服务端拿到后
     * **必须在自己的玩家列表里重新解析**（离线、改名的名字一律解析失败并回绝），
     * 权限也要在服务端重新判定：客户端能打开看板不代表它有权传送别人。
     */
    public static final class PlayerActionC2S {
        public final String action;
        public final String targetUuid;
        public final String targetName;
        public final String arg;

        public PlayerActionC2S(String action, String targetUuid, String targetName, String arg) {
            this.action = action == null ? "" : action;
            this.targetUuid = targetUuid == null ? "" : targetUuid;
            this.targetName = targetName == null ? "" : targetName;
            this.arg = arg == null ? "" : arg;
        }

        public PlayerActionC2S(FriendlyByteBuf buf) {
            this(buf.readUtf(MAX_ID), buf.readUtf(MAX_UUID), buf.readUtf(MAX_NAME), buf.readUtf(MAX_ARG));
        }

        public void encode(FriendlyByteBuf buf) {
            buf.writeUtf(action, MAX_ID);
            buf.writeUtf(targetUuid, MAX_UUID);
            buf.writeUtf(targetName, MAX_NAME);
            buf.writeUtf(arg, MAX_ARG);
        }

        public static void handle(PlayerActionC2S msg, Supplier<NetworkEvent.Context> ctx) {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) return;
            PmServerHandlers.onPlayerAction(player, msg.action, msg.targetUuid, msg.targetName, msg.arg);
        }
    }

    /**
     * 请求 CCNR-RP 的可选角色列表（C2S，空载荷）。
     *
     * <p>由服务端回答「有没有 RP、有哪些角色」，而不是客户端自己看装了没装：
     * 客户端装没装与**服务端能不能部署**是两件事（代理服、单机联机都可能是两套模组集），
     * 真正要执行动作的是服务端，因此答案只能由服务端给。
     */
    public static final class RequestProfessionsC2S {
        public RequestProfessionsC2S() {}

        public RequestProfessionsC2S(FriendlyByteBuf buf) {}

        public void encode(FriendlyByteBuf buf) {}

        public static void handle(RequestProfessionsC2S msg, Supplier<NetworkEvent.Context> ctx) {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) return;
            PmServerHandlers.onRequestProfessions(player);
        }
    }

    /** 可选角色列表（S2C）：{@code available=false} 表示服务端没有 CCNR-RP。 */
    public static final class ProfessionListS2C {
        public final boolean available;
        public final String json;

        public ProfessionListS2C(boolean available, String json) {
            this.available = available;
            this.json = json == null ? "[]" : json;
        }

        public ProfessionListS2C(FriendlyByteBuf buf) {
            this(buf.readBoolean(), buf.readUtf(MAX_PAGE_JSON));
        }

        public void encode(FriendlyByteBuf buf) {
            buf.writeBoolean(available);
            buf.writeUtf(json, MAX_PAGE_JSON);
        }

        public static void handle(ProfessionListS2C msg, Supplier<NetworkEvent.Context> ctx) {
            net.minecraftforge.fml.DistExecutor.unsafeRunWhenOn(
                    net.minecraftforge.api.distmarker.Dist.CLIENT,
                    () -> () -> com.ccnrcom.pm.client.PmClientPacketHandler.onProfessionList(msg.available, msg.json));
        }
    }

    // ------------------------------------------------------------------
    // 管理权限状态（S2C）
    // ------------------------------------------------------------------

    /**
     * 本玩家是否拥有 PM 管理权限。
     *
     * <p><b>为什么需要这个包</b>：在此之前客户端手里**没有任何权限信息**，于是按 P 会无条件打开
     * 管理看板 / 管理面板——普通玩家也能把管理界面打开（界面上什么都没有，因为服务端不给数据，
     * 但门是开着的）。拿到这个标志，客户端才能做"没权限就别开"那道门。
     *
     * <p><b>它不构成安全边界</b>：这只是**界面门**。真正的边界始终在服务端 ——
     * {@code PmServerHandlers} 的每个动作都重新判定 {@code canAdminTicket}，改造过的客户端
     * 即使把标志改成 true，也读不到任何工单数据、做不成任何管理动作。
     *
     * <p>发送时机：登录时下发一次（权威答案）；服务端拒绝某次管理请求时补发 {@code false}，
     * 让"权限被收回后客户端标志仍为 true"这种情况能自我纠正。
     */
    public static final class AdminStateS2C {
        public final boolean admin;

        public AdminStateS2C(boolean admin) {
            this.admin = admin;
        }

        public AdminStateS2C(FriendlyByteBuf buf) {
            this(buf.readBoolean());
        }

        public void encode(FriendlyByteBuf buf) {
            buf.writeBoolean(admin);
        }

        public static void handle(AdminStateS2C msg, Supplier<NetworkEvent.Context> ctx) {
            net.minecraftforge.fml.DistExecutor.unsafeRunWhenOn(
                    net.minecraftforge.api.distmarker.Dist.CLIENT,
                    () -> () -> com.ccnrcom.pm.client.PmClientPacketHandler.onAdminState(msg.admin));
        }
    }

    // ------------------------------------------------------------------
    // 对局（当局状态 / 当局事件流 / 对局管理）
    // ------------------------------------------------------------------

    /**
     * 请求一份对局快照（C2S，空载荷）。
     *
     * <p>由「对局 / 事件」页签发，**面板打开期间每秒一次**：倒计时与事件流是活数据，
     * 而 CCNR-RP 的推送走的是它自己的通道（PM 不订阅对方的包），因此这里必须主动轮询。
     * 面板关闭即停——不做常驻轮询，那是把「看一眼对局」变成一条持续流量。
     */
    public static final class RequestMatchStateC2S {
        public RequestMatchStateC2S() {}

        public RequestMatchStateC2S(FriendlyByteBuf buf) {}

        public void encode(FriendlyByteBuf buf) {}

        public static void handle(RequestMatchStateC2S msg, Supplier<NetworkEvent.Context> ctx) {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) return;
            PmServerHandlers.onRequestMatchState(player);
        }
    }

    /**
     * 对局快照（S2C）。
     *
     * <p>载荷是一整份 JSON（编解码只有 {@code MatchSnapshot} 一份实现）：它由**两块来源**拼成——
     * CCNR-RP 的对局状态（原样内嵌）与 PM 补的幕表/事件表/事件流，用 JSON 才不必为「对方以后多给一个字段」
     * 改动协议两端。上限与工单页共用 {@link #MAX_PAGE_JSON}。
     *
     * <p>{@code available=false}（对方没装 CCNR-RP）是一份**正常载荷**而不是错误：
     * 界面据此显示「未安装 CCNR-RP」并禁用所有动作按钮，而不是永远停在「正在读取……」。
     */
    public static final class MatchStateS2C {
        public final String json;

        public MatchStateS2C(String json) {
            this.json = json == null ? "{\"available\":false}" : json;
        }

        public MatchStateS2C(FriendlyByteBuf buf) {
            this(buf.readUtf(MAX_PAGE_JSON));
        }

        public void encode(FriendlyByteBuf buf) {
            buf.writeUtf(json, MAX_PAGE_JSON);
        }

        public static void handle(MatchStateS2C msg, Supplier<NetworkEvent.Context> ctx) {
            net.minecraftforge.fml.DistExecutor.unsafeRunWhenOn(
                    net.minecraftforge.api.distmarker.Dist.CLIENT,
                    () -> () -> com.ccnrcom.pm.client.PmClientPacketHandler.onMatchState(msg.json));
        }
    }

    /**
     * 对局管理动作（C2S）：切幕 / 触发事件 / 结束事件 / 收束对局 / 重开一局 / 播结束动画。
     *
     * <p>只送「做什么、对哪个目标」。**能不能做、目标存不存在全部由服务端重新判定**——
     * 这里的 {@code arg} 是客户端从快照里复制来的 id，属于**线索**而非授权：
     * 服务端把动作映射到 CCNR-RP 的入口，由对方自己回绝不存在/不可用的目标。
     */
    public static final class MatchActionC2S {
        public final String action;
        public final String arg;

        public MatchActionC2S(String action, String arg) {
            this.action = action == null ? "" : action;
            this.arg = arg == null ? "" : arg;
        }

        public MatchActionC2S(FriendlyByteBuf buf) {
            this(buf.readUtf(MAX_ID), buf.readUtf(MAX_ARG));
        }

        public void encode(FriendlyByteBuf buf) {
            buf.writeUtf(action, MAX_ID);
            buf.writeUtf(arg, MAX_ARG);
        }

        public static void handle(MatchActionC2S msg, Supplier<NetworkEvent.Context> ctx) {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) return;
            PmServerHandlers.onMatchAction(player, msg.action, msg.arg);
        }
    }

    /**
     * 对局动作的结果（S2C）。
     *
     * <p><b>为什么不复用 {@link AdminResultS2C}</b>：那是**工单**动作的结果，客户端收到后写进工单页签的错误行。
     * 两者共用一个包时，客户端只能靠「当前打开的是哪个页签」分流——管理员在对局页签下触发了一个失败的工单动作
     * （或反之）时，提示就会落到看不见的地方。多一个协议号换来的是「提示一定出现在发起它的那个页签上」。
     *
     * <p>成功时也会紧跟一份新的 {@link MatchStateS2C}：两者分开是因为刷新失败不该让「操作成功」这个事实
     * 看起来也失败了（与工单的 AdminResult/TicketList 同一理由）。
     */
    public static final class MatchResultS2C {
        public final boolean ok;
        public final String messageKey;

        public MatchResultS2C(boolean ok, String messageKey) {
            this.ok = ok;
            this.messageKey = messageKey == null ? "" : messageKey;
        }

        public MatchResultS2C(FriendlyByteBuf buf) {
            this(buf.readBoolean(), buf.readUtf(128));
        }

        public void encode(FriendlyByteBuf buf) {
            buf.writeBoolean(ok);
            buf.writeUtf(messageKey, 128);
        }

        public static void handle(MatchResultS2C msg, Supplier<NetworkEvent.Context> ctx) {
            net.minecraftforge.fml.DistExecutor.unsafeRunWhenOn(
                    net.minecraftforge.api.distmarker.Dist.CLIENT,
                    () -> () -> com.ccnrcom.pm.client.PmClientPacketHandler.onMatchResult(msg.ok, msg.messageKey));
        }
    }
}
