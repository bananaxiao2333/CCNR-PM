/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.pm.ticket;

import com.ccnrcom.pm.config.PmConfig;
import com.ccnrcom.pm.data.Database;
import com.ccnrcom.pm.data.DbConfig;
import com.ccnrcom.pm.network.PmChannel;
import com.ccnrcom.pm.permission.PmPermissions;
import com.ccnrcom.pm.util.JsonUtil;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.fml.loading.FMLPaths;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * 工单服务：服务端唯一对外的工单入口。
 *
 * <p>职责边界：
 * <ul>
 *   <li>选择并持有存储后端（数据库优先，连不上则回退文件）</li>
 *   <li>受理玩家提交：**重新校验**客户端说的一切，再落库</li>
 *   <li>管理动作（列表/认领/办结/驳回）与在线管理员提醒</li>
 * </ul>
 *
 * <p>线程纪律：所有方法都必须在**主线程**调用（需要 Level / ServerPlayer 状态），
 * 因此这里不做任何异步；数据库写本身是同步的（工单提交频率很低，不值得为它引入异步复杂度）。
 */
public final class TicketService {

    private static final Logger LOGGER = LogManager.getLogger("ccnr_pm");

    private static TicketService instance;

    private final TicketStore store;
    private final Database database;

    private TicketService(TicketStore store, Database database) {
        this.store = store;
        this.database = database;
    }

    /** 当前实例；未初始化时返回 null（命令与网络处理器据此给出「尚未就绪」提示）。 */
    public static TicketService get() {
        return instance;
    }

    /**
     * 启动时初始化。
     *
     * @param worldDir {@code world/ccnr_pm}（文件后端与默认数据目录）
     * @param configDir {@code config/ccnr_pm}（db.properties 与举报类别定义）
     */
    public static void init(Path worldDir, Path configDir) {
        shutdown();
        DbConfig dbConfig = DbConfig.load(configDir.resolve("db.properties"));
        Database db = null;
        if (dbConfig.enabled()) {
            db = new Database(dbConfig);
            if (!db.connect() || !db.usable()) {
                // 配置开了库但连不上：明确回退文件，绝不让工单写进一个不可用的后端
                LOGGER.error("[CCNR-PM] 数据库不可用，工单回退文件存储: {}", worldDir.resolve("tickets.json"));
                db.disconnect();
                db = null;
            }
        }
        TicketStore store;
        if (db != null) {
            JdbcTicketStore jdbc = new JdbcTicketStore(db);
            store = jdbc.init() ? jdbc : null;
        } else {
            store = null;
        }
        if (store == null) {
            JsonTicketStore json = new JsonTicketStore(worldDir.resolve("tickets.json"));
            json.init();
            store = json;
        }
        instance = new TicketService(store, db);
        loadCategories(configDir);
        LOGGER.info("[CCNR-PM] 工单存储后端: {}", store.backend());
    }

    public static void shutdown() {
        if (instance != null) {
            try {
                instance.store.close();
            } catch (Exception e) {
                LOGGER.error("[CCNR-PM] 关闭工单存储失败: {}", e.toString());
            }
            if (instance.database != null) instance.database.disconnect();
            instance = null;
        }
    }

    /** 从配置文件载入举报类别；文件缺失/损坏时回退内置六类并写出模板。 */
    public static void loadCategories(Path configDir) {
        Path file = configDir.resolve("report_categories.json");
        if (!java.nio.file.Files.isRegularFile(file)) {
            writeCategoryTemplate(file);
        }
        CategoryRegistry parsed =
                JsonUtil.readObject(file).map(CategoryRegistry::parse).orElse(null);
        if (parsed == null) {
            LOGGER.warn("[CCNR-PM] 举报类别文件无效，使用内置六类: {}", file);
            CategoryRegistry.install(new CategoryRegistry(CategoryRegistry.defaults()));
        } else {
            CategoryRegistry.install(parsed);
        }
    }

    private static void writeCategoryTemplate(Path file) {
        com.google.gson.JsonObject root = new com.google.gson.JsonObject();
        com.google.gson.JsonArray arr = new com.google.gson.JsonArray();
        int order = 10;
        for (String id : CategoryRegistry.DEFAULT_IDS) {
            com.google.gson.JsonObject o = new com.google.gson.JsonObject();
            o.addProperty("id", id);
            o.addProperty("order", order);
            o.addProperty("enabled", true);
            o.addProperty("$comment", "显示名取语言键 ccnr_pm.category." + id + ".name；也可用 name 字段直接写死");
            arr.add(o);
            order += 10;
        }
        root.add("categories", arr);
        JsonUtil.atomicWrite(file, root);
    }

    // ------------------------------------------------------------------
    // 玩家侧
    // ------------------------------------------------------------------

    /** 提交结果：成功则带工单；失败则带全部校验错误（客户端逐条显示）。 */
    public record SubmitResult(boolean ok, Ticket ticket, List<ReportValidator.Error> errors) {
        public static SubmitResult fail(ReportValidator.Error e) {
            return new SubmitResult(false, null, List.of(e));
        }

        public static SubmitResult fail(List<ReportValidator.Error> es) {
            return new SubmitResult(false, null, List.copyOf(es));
        }
    }

    public static final String ERR_NOT_READY = "ccnr_pm.report.err.not_ready";

    /**
     * 关联玩家不存在（既不在线、也不在本服「见过面」的名录里）。
     *
     * <p>为什么判据是「在线 或 见过面」而不是「必须在线」：人刚下线、或改名之后照样得能举报，
     * 那是最需要被记录的时刻；但随手打一个不存在的名字应该当场被拒——那会变成一张指向空气的工单。
     */
    public static final String ERR_TARGET_UNKNOWN = "ccnr_pm.report.err.target_unknown";

    /**
     * 受理一次举报提交。
     *
     * <p>**服务端重新校验的全部内容**：类别是否启用、内容与描述长度、关联玩家数量与名字长度、
     * 冷却与并发上限。客户端面板上做过同样的校验只是为了即时反馈，这里的结论才是权威。
     *
     * <p>关联玩家是**选填**的，而且可能写出一个当时不在线的名字。这类名字**不拒绝**：
     * 能解析到在线玩家的就附上 UUID，解析不到的只留名字。理由见 {@link ReportValidator} 的类注释。
     */
    public SubmitResult submit(ServerPlayer reporter, ReportDraft draft) {
        if (reporter == null) return SubmitResult.fail(ReportValidator.Error.of(ERR_NOT_READY));
        MinecraftServer server = reporter.server;
        if (server == null) return SubmitResult.fail(ReportValidator.Error.of(ERR_NOT_READY));

        // 1) 纯校验（与客户端同一套规则）
        List<ReportValidator.Error> errors =
                ReportValidator.validate(draft, CategoryRegistry.active(), PmConfig.limits());
        if (!errors.isEmpty()) return SubmitResult.fail(errors);

        // 2) 解析关联玩家：在线的补上 UUID（名单是开面板那一刻的快照，所以此刻重新查一遍）；
        //    **本服见过面的**离线玩家也能补上 UUID（人刚下线/改名照样能举报，看板头像也还查得到）
        List<String> names = ReportValidator.parseTargets(draft.targetRaw());

        // 2b) 关联玩家合法性：**在线 或 本服见过面**才算合法（用户要求「校验目标是否合法」）。
        //     判据在服务端重新算——面板上的即时提示只是体验，被改造过的客户端塞进来的陌生名字
        //     会在这里被拒，而不是变成一张指向空气、管理员查不到人的工单。
        List<String> unknown = new ArrayList<>();
        for (String name : names) {
            if (findOnline(server, name) != null) continue;
            if (com.ccnrcom.pm.player.KnownPlayers.isKnown(name)) continue;
            unknown.add(name);
        }
        if (!unknown.isEmpty()) {
            return SubmitResult.fail(ReportValidator.Error.of(ERR_TARGET_UNKNOWN, String.join("、", unknown)));
        }

        List<TicketTarget> targets = resolveTargets(server, names);

        // 3) 防刷闸门（纯服务端概念：客户端面板不知道也不该知道冷却与并发上限的存在）。
        //    规则本身在 SubmitGate 里，边界由 SubmitGateTest 逐点钉死。
        long now = System.currentTimeMillis();
        SubmitGate.Limits abuse =
                new SubmitGate.Limits(PmConfig.COOLDOWN_SECONDS.get(), PmConfig.MAX_OPEN_PER_PLAYER.get());
        SubmitGate.History history = new SubmitGate.History(
                store.lastSubmitAt(reporter.getUUID()), store.countUnresolvedByReporter(reporter.getUUID()));
        Optional<ReportValidator.Error> blocked = SubmitGate.check(abuse, history, now);
        if (blocked.isPresent()) return SubmitResult.fail(blocked.get());

        // 4) 落库
        Optional<Ticket> created = store.insert(new TicketStore.NewTicket(
                reporter.getUUID(),
                reporter.getGameProfile().getName(),
                targets,
                draft.categoryId(),
                draft.content(),
                draft.detail(),
                now));
        if (created.isEmpty()) return SubmitResult.fail(ReportValidator.Error.of(ERR_NOT_READY));

        Ticket t = created.get();
        notifyAdmins(server, t);
        // 新工单进入活跃集合 → 看板整份刷新（新卡片出现在管理员左上角并**常驻**）
        broadcastBoard(server);
        return new SubmitResult(true, t, List.of());
    }

    /**
     * 把玩家输入的名字解析成关联玩家列表。
     *
     * <p>在线 → 附 UUID（便于日后按人检索）；不在线 → 只留名字。
     * 两条路径都不丢弃输入，因为「写错了一个当时不在线的名字」不该让整张工单作废。
     */
    private static List<TicketTarget> resolveTargets(MinecraftServer server, List<String> names) {
        List<TicketTarget> out = new ArrayList<>(names.size());
        for (String name : names) {
            ServerPlayer p = findOnline(server, name);
            if (p != null) {
                out.add(new TicketTarget(p.getUUID(), p.getGameProfile().getName()));
                continue;
            }
            // 离线但本服见过面：用名录里的 UUID 与**规范名字**（手输的大小写常与真实名字不同）。
            // 带上 UUID 后看板头像仍能按 uuid 命中缓存，也不会把同一个人因大小写记成两条。
            Optional<java.util.UUID> known = com.ccnrcom.pm.player.KnownPlayers.uuidOf(name);
            out.add(known.map(uuid -> new TicketTarget(uuid, com.ccnrcom.pm.player.KnownPlayers.canonicalName(name)))
                    .orElseGet(() -> TicketTarget.byName(name)));
        }
        return List.copyOf(out);
    }

    /** 在线玩家里按名字查（忽略大小写）；找不到返回 null。 */
    private static ServerPlayer findOnline(MinecraftServer server, String name) {
        if (name == null || name.isBlank()) return null;
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (p.getGameProfile().getName().equalsIgnoreCase(name)) return p;
        }
        return null;
    }

    /** 提醒在线管理员：新工单需要有人认领，否则会烂在队列里。 */
    private void notifyAdmins(MinecraftServer server, Ticket t) {
        if (!PmConfig.NOTIFY_ADMINS.get()) return;

        // 聊天提醒**立即**发出：它是持久、可回看的记录，不该为了等头像而延迟。
        // 左上角那张卡片由 {@link #broadcastBoard} 负责（常驻看板），不在这里发。
        Component msg = Component.translatable(
                "ccnr_pm.ticket.notify",
                t.label(),
                t.reporterName(),
                t.targetLabel(),
                Component.translatable("ccnr_pm.category." + t.categoryId() + ".name"));
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (PmPermissions.canAdminTicket(p)) {
                p.sendSystemMessage(msg);
            }
        }

        // 2) 顺带预取参与者的头像：看板卡片要显示头像。
        //    抓取是异步的、且**有超时**——皮肤站很慢也不能把卡片卡住，
        //    到点就用已有头像把看板发出去，抓到的下一轮自动生效。
        if (!PmConfig.AVATAR_ENABLED.get()) return;

        List<com.ccnrcom.pm.avatar.AvatarService.PlayerRef> refs = new ArrayList<>();
        ServerPlayer reporter =
                t.reporterUuid() == null ? null : server.getPlayerList().getPlayer(t.reporterUuid());
        if (reporter != null) {
            com.ccnrcom.pm.avatar.AvatarService.PlayerRef ref =
                    com.ccnrcom.pm.avatar.AvatarService.PlayerRef.of(reporter);
            if (ref != null) refs.add(ref);
        }
        for (TicketTarget target : t.targets()) {
            if (target.uuid() == null) continue;
            ServerPlayer p = server.getPlayerList().getPlayer(target.uuid());
            if (p == null) continue;
            com.ccnrcom.pm.avatar.AvatarService.PlayerRef ref = com.ccnrcom.pm.avatar.AvatarService.PlayerRef.of(p);
            if (ref != null) refs.add(ref);
        }
        if (refs.isEmpty()) return;

        // 抓完后刷新一次看板，让头像补上（本轮看板已在 submit 里发过）
        com.ccnrcom.pm.avatar.AvatarService.prefetch(refs, PmConfig.AVATAR_TIMEOUT_MS.get())
                .thenAccept(ignored -> server.execute(() -> broadcastBoard(server)));
    }

    /** 把某个玩家已缓存到的头像放进待发包体；没有就跳过（客户端会回退到自己解析的皮肤）。 */
    private static void collectAvatar(Map<String, String> out, java.util.UUID uuid) {
        if (uuid == null) return;
        com.ccnrcom.pm.avatar.AvatarService.cached(uuid).ifPresent(b64 -> out.put(uuid.toString(), b64));
    }

    /**
     * 忽略 / 取消忽略某工单（**按管理员隔离**，不改变工单状态）。
     *
     * <p>命令（{@code /pm ticket ignore|unignore}）与面板动作共用这一个入口，
     * 避免两处各写一遍导致行为分叉。
     *
     * @return 是否真的改变了状态（重复忽略同一张返回 false，不算失败）
     */
    public boolean changeIgnore(ServerPlayer admin, String ticketId, boolean ignored) {
        if (admin == null || ticketId == null || ticketId.isBlank()) return false;
        boolean changed = store.setIgnored(admin.getUUID(), ticketId, ignored);
        if (changed) broadcastBoard(admin.server);
        return changed;
    }

    /** 面板用：当前在线玩家（含查看者自己，由客户端自行排除）。 */
    public List<OnlinePlayer> roster(MinecraftServer server) {
        List<OnlinePlayer> out = new ArrayList<>();
        if (server == null) return out;
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            out.add(new OnlinePlayer(p.getUUID().toString(), p.getGameProfile().getName()));
        }
        return out;
    }

    /** 在线玩家条目（S2C 下发）。 */
    public record OnlinePlayer(String uuid, String name) {}

    // ------------------------------------------------------------------
    // 管理侧
    // ------------------------------------------------------------------

    public List<Ticket> list(TicketStatus filter, int limit, int offset) {
        return store.list(filter, limit, offset);
    }

    public int count(TicketStatus filter) {
        return store.count(filter);
    }

    public Optional<Ticket> byId(String id) {
        return store.byId(id);
    }

    /** 按标识前缀查找（玩家只会口述短号）。返回多条表示前缀有歧义，调用方必须报错而不是猜。 */
    public List<Ticket> byIdPrefix(String prefix, int limit) {
        return store.byIdPrefix(prefix, limit);
    }

    /** 认领：只允许从 OPEN 迁移，避免两位管理员同时认领同一条。 */
    public boolean claim(ServerPlayer admin, Ticket t) {
        if (admin == null || t == null) return false;
        if (t.status() != TicketStatus.OPEN) return false;
        return store.updateStatus(
                t.id(), TicketStatus.CLAIMED, admin.getGameProfile().getName(), System.currentTimeMillis(), null);
    }

    /** 办结 / 驳回。 */
    public boolean resolve(ServerPlayer admin, Ticket t, TicketStatus status, String note) {
        if (admin == null || t == null) return false;
        if (status != TicketStatus.CLOSED && status != TicketStatus.REJECTED) return false;
        if (!t.status().isUnresolved()) return false;
        return store.updateStatus(t.id(), status, admin.getGameProfile().getName(), System.currentTimeMillis(), note);
    }

    // ------------------------------------------------------------------
    // 左上角常驻看板
    // ------------------------------------------------------------------

    /** 看板最多显示多少张卡片；超出的由客户端折叠成「+N」。 */
    public static final int BOARD_LIMIT = 30;

    /**
     * 构建当前看板数据（活跃工单 + 已缓存的头像）。
     *
     * <p>只带看板卡片真正会画的字段（见 {@link TicketBoard} 的类注释），
     * 不拖上正文/描述——管理员登录时要一次性拿十几张卡片，正文在这里一个字节都不显示。
     *
     * <p>头像**只读缓存不触发抓取**：登录时对十几个人发起 HTTP 会让看板迟迟不出来。
     * 抓取交给登录预热（见 {@code CCNRPMMod.onPlayerLoggedIn}）与建单路径。
     */
    public TicketBoard board(java.util.UUID viewer) {
        List<Ticket> active = store.listActive(BOARD_LIMIT);
        java.util.Set<String> ignored = viewer == null ? java.util.Set.of() : store.ignoredIds(viewer);
        List<TicketBoard.Card> cards = new ArrayList<>(active.size());
        for (Ticket t : active) {
            // 忽略是**按管理员隔离**的：只影响这位管理员自己的看板
            if (ignored.contains(t.id())) continue;
            Map<String, String> avatars = new java.util.HashMap<>();
            collectAvatar(avatars, t.reporterUuid());
            for (TicketTarget target : t.targets()) {
                collectAvatar(avatars, target.uuid());
            }
            cards.add(TicketBoard.cardOf(t, avatars));
        }
        return new TicketBoard(cards);
    }

    /**
     * 把看板推给所有在线管理员。
     *
     * <p>推「整份」而不是增量：哪张工单还算活跃只有服务端知道，
     * 整份替换让客户端不可能出现「以为还在看板上、其实早就办结了」。
     *
     * <p>调用点：玩家登录（管理员）、新建工单、以及任何会改变活跃集合的动作
     * （认领不改变活跃集合，但仍推送以更新卡片状态）。
     */
    public void broadcastBoard(MinecraftServer server) {
        if (server == null) return;
        List<ServerPlayer> admins = new ArrayList<>();
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (PmPermissions.canAdminTicket(p)) admins.add(p);
        }
        if (admins.isEmpty()) return;
        // 每个管理员的忽略名单不同，因此**逐人构建**其看板，
        // 不能算一份 JSON 群发（那会把别人忽略掉的卡片也推给他）
        for (ServerPlayer p : admins) {
            PmChannel.sendTo(
                    p,
                    new com.ccnrcom.pm.network.PmPackets.TicketBoardS2C(
                            board(p.getUUID()).toJsonString()));
        }
    }

    /** 把看板推给单个玩家（登录时用）。 */
    public void sendBoard(ServerPlayer player) {
        if (player == null) return;
        PmChannel.sendTo(
                player,
                new com.ccnrcom.pm.network.PmPackets.TicketBoardS2C(
                        board(player.getUUID()).toJsonString()));
    }

    // ------------------------------------------------------------------
    // 管理面板
    // ------------------------------------------------------------------

    /** 面板可选的筛选值。{@code all} 表示不过滤。 */
    public static final List<String> FILTERS = List.of("all", "open", "claimed", "closed", "rejected");

    /** 把筛选字面量解析为状态；无法识别或 {@code all} 返回 null（不过滤）。 */
    public static TicketStatus parseFilter(String filter) {
        if (filter == null || filter.isBlank() || "all".equalsIgnoreCase(filter.trim())) return null;
        for (TicketStatus st : TicketStatus.values()) {
            if (st.id().equalsIgnoreCase(filter.trim())) return st;
        }
        return null;
    }

    /**
     * 组装面板的一页数据。
     *
     * <p>筛选值无法识别时按「全部」处理而不是报错：面板的筛选是点击出来的，
     * 一个坏值不该让整个页面打不开。
     */
    public TicketPage page(String filter, int page, int pageSize, String viewerName) {
        TicketStatus st = parseFilter(filter);
        int size = Math.max(1, pageSize);

        // 排序在**服务端**做（见 TicketSort）：客户端只按顺序画，
        // 避免两处各写一套排序规则、迟早不一致。
        // 注意：排序要对**全部**工单做，再切页——先切页再排序会得到错乱的页。
        List<Ticket> all = store.list(st, Integer.MAX_VALUE, 0);
        List<TicketPage.Entry> entries = new ArrayList<>(all.size());
        for (Ticket t : all) entries.add(TicketPage.Entry.of(t));
        entries.sort(TicketSort.comparator(viewerName));

        int total = entries.size();
        int pages = Math.max(1, (total + size - 1) / size);
        int current = Math.max(1, Math.min(page, pages));
        int from = Math.min((current - 1) * size, total);
        int to = Math.min(from + size, total);
        List<TicketPage.Entry> window = List.copyOf(entries.subList(from, to));

        return new TicketPage(st == null ? "all" : st.id(), current, size, total, window);
    }

    /** 管理动作的结果。 */
    public enum ActionOutcome {
        OK,
        NOT_FOUND,
        BAD_STATE,
        UNKNOWN_ACTION
    }

    /**
     * 执行一个管理动作。
     *
     * <p>**服务端权威**：动作名、目标状态、身份全部由这里重新判定——
     * 客户端只送来「我想对哪张工单做什么」，能不能做由服务端说了算。
     * 前端的禁用/置灰只是体验，不构成约束。
     */
    public ActionOutcome act(ServerPlayer admin, String action, String ticketId, String note) {
        if (admin == null) return ActionOutcome.UNKNOWN_ACTION;
        // 前缀命中多条时按「找不到」处理：面板永远送完整 id，出现歧义说明客户端被改造过
        List<Ticket> found = store.byIdPrefix(ticketId, 2);
        if (found.size() != 1) return ActionOutcome.NOT_FOUND;
        Ticket t = found.get(0);

        boolean ok;
        switch (action == null ? "" : action.toLowerCase(java.util.Locale.ROOT)) {
            case "claim" -> {
                ok = claim(admin, t);
                if (ok) broadcastBoard(admin.server);
                return ok ? ActionOutcome.OK : ActionOutcome.BAD_STATE;
            }
            case "close" -> {
                ok = resolve(admin, t, TicketStatus.CLOSED, note);
                if (ok) {
                    notifyReporterOf(admin, t, TicketStatus.CLOSED, note);
                    broadcastBoard(admin.server); // 办结后这张卡片离开看板
                }
                return ok ? ActionOutcome.OK : ActionOutcome.BAD_STATE;
            }
            case "reject" -> {
                ok = resolve(admin, t, TicketStatus.REJECTED, note);
                if (ok) {
                    notifyReporterOf(admin, t, TicketStatus.REJECTED, note);
                    broadcastBoard(admin.server);
                }
                return ok ? ActionOutcome.OK : ActionOutcome.BAD_STATE;
            }
            case "ignore" -> {
                changeIgnore(admin, t.id(), true);
                return ActionOutcome.OK;
            }
            case "unignore" -> {
                changeIgnore(admin, t.id(), false);
                return ActionOutcome.OK;
            }
            default -> {
                return ActionOutcome.UNKNOWN_ACTION;
            }
        }
    }

    /**
     * 面板动作后告知举报人。
     *
     * <p>与命令路径的通知合并到一处：命令与面板是同一个动作的两种入口，
     * 各自写一份文案迟早不一致。
     */
    private void notifyReporterOf(ServerPlayer admin, Ticket t, TicketStatus target, String note) {
        MinecraftServer server = admin == null ? null : admin.server;
        if (server == null) return;
        ServerPlayer reporter = server.getPlayerList().getPlayer(t.reporterUuid());
        if (reporter == null) return;
        String by = admin.getGameProfile().getName();
        String noteText = note == null || note.isBlank() ? "-" : note;
        reporter.sendSystemMessage(Component.translatable(
                        target == TicketStatus.CLOSED ? "ccnr_pm.ticket.resolved" : "ccnr_pm.ticket.rejected",
                        t.label(),
                        noteText)
                .withStyle(
                        target == TicketStatus.CLOSED
                                ? net.minecraft.ChatFormatting.GREEN
                                : net.minecraft.ChatFormatting.YELLOW));
        reporter.sendSystemMessage(
                Component.translatable("ccnr_pm.ticket.handled_by", by).withStyle(net.minecraft.ChatFormatting.GRAY));
    }

    // ------------------------------------------------------------------
    // 诊断
    // ------------------------------------------------------------------

    public String backendName() {
        return store.backend();
    }

    public boolean databaseEnabled() {
        return database != null && database.usable();
    }

    public boolean ping() {
        return database == null || database.ping();
    }

    /** 配置目录（命令重载用）。 */
    public static Path defaultConfigDir() {
        return FMLPaths.CONFIGDIR.get().resolve("ccnr_pm");
    }

    /** 把新工单播报给客户端面板（S2C）；频道不存在时静默跳过。 */
    public void sendOpenPanel(ServerPlayer player, String prefill) {
        if (player == null) return;
        MinecraftServer server = player.server;
        ReportValidator.Limits limits = PmConfig.limits();
        PmChannel.sendTo(
                player,
                new com.ccnrcom.pm.network.PmPackets.OpenReportScreenS2C(
                        prefill == null ? "" : prefill,
                        roster(server),
                        CategoryRegistry.active().enabled(),
                        limits.maxContent(),
                        limits.maxDetail()));
    }
}
