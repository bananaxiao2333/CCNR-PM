/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.pm.avatar;

import com.ccnrcom.pm.util.JsonUtil;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import net.minecraft.server.level.ServerPlayer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * 服务端头像缓存：把玩家的皮肤 PNG 抓下来存成 base64，供通知卡片使用。
 *
 * <h2>为什么必须服务端自己抓</h2>
 * 本模组面向**第三方服务器**（多为离线验证）。玩家客户端不一定能解析出真实头像：
 * 离线 UUID 在 Mojang 皮肤库里查无此人，客户端只会拿到一个默认皮肤。
 * 所以头像必须由服务端在**建单前**抓取并连同通知一起下发——这样卡片上显示的是
 * 「这张工单相关的玩家当时长什么样」，与客户端能不能联网取皮肤无关。
 *
 * <h2>数据来源与它的边界（重要）</h2>
 * 皮肤 URL 来自玩家 {@code GameProfile} 的 {@code textures} 属性，它由**服务端**填充：
 * <ul>
 *   <li>正版验证（online-mode）—— 有该属性，能拿到真实皮肤</li>
 *   <li>装了皮肤插件（SkinsRestorer 一类）或自建皮肤站 —— 有该属性，能拿到真实皮肤</li>
 *   <li>纯离线且无任何皮肤方案 —— **没有该属性**，抓不到，此处记为「已知无头像」并记一次日志</li>
 * </ul>
 * 第三种情况不是缺陷，是「服务端根本没有这个信息」；客户端侧仍会回退到它自己解析的皮肤。
 *
 * <h2>缓存语义</h2>
 * 全局共享、**新抓到的覆盖旧的**（玩家换皮肤后，下一次抓取即生效），并落盘到
 * {@code config/ccnr_pm/avatars.json}，重启后不必重抓。超出容量上限时按抓取时间淘汰最旧的。
 *
 * <h2>硬约束：只能在玩家**在线**时抓</h2>
 * 皮肤 URL 来自该玩家 `GameProfile` 的 `textures` 属性，而那个属性只有在线的 `ServerPlayer` 上才有。
 * 一旦玩家下线，服务端**再也拿不到他的头像**——离线 UUID 在皮肤库里查无此人。
 *
 * <p>由此推出一条必须遵守的规则：**每个玩家登录时都要抓（而不是只抓管理员）**。
 * 曾经为了「省一次 HTTP」只抓管理员，结果管理员看板上的其他玩家全变成默认皮肤，
 * 重启后尤其明显（内存缓存清空，而那些人未必马上登录）。
 * 抓取成本是每人每次登录一次 HTTP，换取的是「谁的头像都在」。
 *
 * <h2>线程边界</h2>
 * HTTP 与 PNG 解码全部在 {@link #POOL} 的守护线程上；该线程**只碰不可变数据**
 * （{@link PlayerRef} 快照与字节数组），绝不触碰 Level / 实体 / 网络 / 渲染。
 * 需要发包时由调用方回到服务端主线程（{@code server.execute}）。
 */
public final class AvatarService {

    private static final Logger LOGGER = LogManager.getLogger("ccnr_pm");

    /** 单个皮肤 PNG 的字节上限；超过的一律丢弃（正常皮肤 64×64 只有几 KB）。 */
    private static final int MAX_PNG_BYTES = 64 * 1024;

    /** 缓存条目上限，超出按抓取时间淘汰最旧的。 */
    private static final int MAX_ENTRIES = 200;

    /** 落盘节流：两次写盘至少间隔这么久，避免每次抓取都 fsync 一次。 */
    private static final long WRITE_THROTTLE_MS = 30_000L;

    private static final ExecutorService POOL = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "ccnr-pm-avatar");
        t.setDaemon(true);
        return t;
    });

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .executor(POOL)
            .build();

    /** uuid → 头像条目。 */
    private static final Map<UUID, Entry> CACHE = new ConcurrentHashMap<>();

    /** 正在抓取的 uuid，避免同一个人被并发抓多次。 */
    private static final Set<UUID> IN_FLIGHT = ConcurrentHashMap.newKeySet();

    /** 已知抓不到的 uuid（无 textures 属性 / 下载失败），避免每次建单都重试一遍。 */
    private static final Set<UUID> NEGATIVE = ConcurrentHashMap.newKeySet();

    private static volatile Path file;
    private static volatile long lastWriteMs;
    private static volatile boolean dirty;

    private AvatarService() {}

    /** 缓存条目。{@code base64} 是完整皮肤 PNG（客户端自行截取脸部区域）。 */
    private record Entry(String name, long fetchedAt, String base64) {}

    /**
     * 玩家身份快照（**必须在主线程构造**）。
     *
     * <p>把 {@code textures} 属性在这里取出来存死：HTTP 线程上不能再碰 {@link ServerPlayer}，
     * 否则就是在非主线程读实体状态，是明确禁止的。
     */
    public record PlayerRef(UUID uuid, String name, String texturesProperty) {

        public static PlayerRef of(ServerPlayer player) {
            if (player == null) return null;
            String tex = player.getGameProfile().getProperties().get("textures").stream()
                    .findFirst()
                    .map(p -> p.getValue())
                    .orElse(null);
            return new PlayerRef(player.getUUID(), player.getGameProfile().getName(), tex);
        }
    }

    // ------------------------------------------------------------------
    // 生命周期
    // ------------------------------------------------------------------

    /**
     * 载入落盘缓存。失败不影响功能（只是需要重新抓一遍）。
     *
     * <p>{@code configDir} 为 null 时退化为**纯内存缓存**：不落盘，但抓取与下发照常工作。
     * 早期版本在这里直接解引用路径，会在配置目录不可用时把整个初始化打断。
     */
    public static void init(Path configDir) {
        file = configDir == null ? null : configDir.resolve("avatars.json");
        CACHE.clear();
        NEGATIVE.clear();
        JsonUtil.readObject(file).ifPresent(root -> {
            if (!root.has("avatars") || !root.get("avatars").isJsonObject()) return;
            JsonObject avatars = root.getAsJsonObject("avatars");
            for (Map.Entry<String, JsonElement> e : avatars.entrySet()) {
                if (!e.getValue().isJsonObject()) continue;
                try {
                    UUID id = UUID.fromString(e.getKey());
                    JsonObject o = e.getValue().getAsJsonObject();
                    String b64 = JsonUtil.str(o, "png", "");
                    if (b64.isBlank()) continue;
                    CACHE.put(id, new Entry(JsonUtil.str(o, "name", ""), JsonUtil.num(o, "fetchedAt", 0L), b64));
                } catch (IllegalArgumentException ignored) {
                    // 坏键跳过，不影响其它条目
                }
            }
            LOGGER.info("[CCNR-PM] 已载入 {} 个头像缓存", CACHE.size());
        });
    }

    /** 关闭：强制落盘并回收线程池（对称清理）。 */
    public static void shutdown() {
        save(true);
        IN_FLIGHT.clear();
        NEGATIVE.clear();
    }

    // ------------------------------------------------------------------
    // 读取
    // ------------------------------------------------------------------

    /** 已缓存的头像（base64 PNG）。 */
    public static Optional<String> cached(UUID uuid) {
        if (uuid == null) return Optional.empty();
        Entry e = CACHE.get(uuid);
        return e == null ? Optional.empty() : Optional.of(e.base64());
    }

    public static int size() {
        return CACHE.size();
    }

    /** 已知抓不到头像的玩家数（诊断用：这个数很大就说明当前环境拿不到皮肤）。 */
    public static int negativeCount() {
        return NEGATIVE.size();
    }

    /**
     * 清掉某个玩家的「抓不到」标记，允许下次登录再试一次。
     *
     * <p>为什么需要它：抓取失败会进 {@link #NEGATIVE} 以避免每次建单都重试。但若失败是**暂时**的
     * （皮肤站抖了一下、网络闪断），这个人就会在整个服务端进程生命周期内都没有头像。
     * 玩家每次登录都是一次新的机会，因此登录时先解除标记。
     */
    public static void forgetNegative(UUID uuid) {
        if (uuid != null) NEGATIVE.remove(uuid);
    }

    /** 缓存里是否有该玩家的头像（诊断用）。 */
    public static boolean has(UUID uuid) {
        return uuid != null && CACHE.containsKey(uuid);
    }

    /**
     * 预取一批玩家的头像，返回「全部结束或超时」的 future。
     *
     * <p>**超时是刻意设计**：抓取失败或对方皮肤站很慢时，不能把通知卡在那里——
     * 到点就用已有的头像把卡片发出去，缺失的由客户端回退处理。已经在飞的请求不会被打断，
     * 抓到后照常进缓存，下一张工单就能用上。
     *
     * @param refs 主线程构造的身份快照
     * @param timeoutMs 等待上限
     */
    public static CompletableFuture<Void> prefetch(Collection<PlayerRef> refs, long timeoutMs) {
        if (refs == null || refs.isEmpty()) return CompletableFuture.completedFuture(null);
        List<CompletableFuture<Void>> pending = new ArrayList<>();
        for (PlayerRef ref : refs) {
            if (ref == null || ref.uuid() == null) continue;
            if (CACHE.containsKey(ref.uuid()) || NEGATIVE.contains(ref.uuid())) continue;
            pending.add(fetchOne(ref));
        }
        if (pending.isEmpty()) return CompletableFuture.completedFuture(null);
        CompletableFuture<Void> all = CompletableFuture.allOf(pending.toArray(new CompletableFuture<?>[0]));
        return all.orTimeout(Math.max(1L, timeoutMs), TimeUnit.MILLISECONDS).exceptionally(t -> null);
    }

    // ------------------------------------------------------------------
    // 抓取
    // ------------------------------------------------------------------

    private static CompletableFuture<Void> fetchOne(PlayerRef ref) {
        String textures = ref.texturesProperty();
        boolean needsLookup = textures == null || textures.isBlank();
        if (needsLookup && !mojangLookupEnabled()) {
            // 服务端没有该玩家的皮肤信息（纯离线且无皮肤方案），且管理员关掉了按名字反查：记一次就够了
            if (NEGATIVE.add(ref.uuid())) {
                LOGGER.info(
                        "[CCNR-PM] 玩家 {} 的 GameProfile 不含 textures 属性，无法获取头像"
                                + "（离线服务器可装皮肤插件，或开启 avatar.mojangLookup 按名字反查；客户端会回退到自己解析的皮肤）",
                        ref.name());
            }
            return CompletableFuture.completedFuture(null);
        }
        if (!IN_FLIGHT.add(ref.uuid())) return CompletableFuture.completedFuture(null);

        return CompletableFuture.runAsync(
                () -> {
                    try {
                        String url = needsLookup
                                ? lookupSkinUrlByName(ref.name())
                                : SkinUrl.fromTexturesProperty(textures).orElse(null);
                        if (url == null) {
                            NEGATIVE.add(ref.uuid());
                            if (needsLookup) {
                                LOGGER.info(
                                        "[CCNR-PM] 玩家 {} 在 Mojang 查不到皮肤（多半是离线名）：{}{}",
                                        ref.name(),
                                        MOJANG_PROFILE_API,
                                        ref.name());
                            }
                            return;
                        }
                        byte[] png = download(url);
                        if (png == null) {
                            NEGATIVE.add(ref.uuid());
                            LOGGER.warn("[CCNR-PM] 下载头像失败: player={} url={}", ref.name(), url);
                            return;
                        }
                        String b64 = Base64.getEncoder().encodeToString(png);
                        put(ref.uuid(), ref.name(), b64);
                        if (needsLookup) {
                            LOGGER.info("[CCNR-PM] 已按名字从 Mojang 取到 {} 的皮肤（离线服回退）", ref.name());
                        }
                    } catch (Exception e) {
                        NEGATIVE.add(ref.uuid());
                        LOGGER.error("[CCNR-PM] 抓取头像异常: player={} —— {}", ref.name(), e.toString());
                    } finally {
                        IN_FLIGHT.remove(ref.uuid());
                    }
                },
                POOL);
    }

    // ------------------------------------------------------------------
    // 离线服回退：按名字去 Mojang 反查皮肤
    // ------------------------------------------------------------------

    private static final String MOJANG_PROFILE_API = "https://api.mojang.com/users/profiles/minecraft/";

    private static final String MOJANG_SESSION_API = "https://sessionserver.mojang.com/session/minecraft/profile/";

    /** 用户名白名单：只放行正版名允许的字符，免得为乱码名字白跑两次 HTTP。 */
    private static final java.util.regex.Pattern USERNAME = java.util.regex.Pattern.compile("^[A-Za-z0-9_]{3,16}$");

    private static boolean mojangLookupEnabled() {
        try {
            return com.ccnrcom.pm.config.PmConfig.AVATAR_MOJANG_LOOKUP.get();
        } catch (Exception e) {
            // 配置未加载（纯单测/工具路径）：按关闭处理，避免在非服务端环境发网络请求
            return false;
        }
    }

    /**
     * 按玩家名反查皮肤 URL（两步：名字 → 正版 UUID → 档案里的 textures 属性）。
     *
     * <h2>为什么需要它</h2>
     * 皮肤 URL 只在 {@code GameProfile.textures} 里，而**纯离线服务器**不会填这个属性，
     * 于是所有头像都变默认——用户反馈的「获取不到真实头像」多半就是这个场景。
     * 正版名在 Mojang 有档案，查一次就能拿到真实皮肤；盗版名查不到，负缓存后不再重试。
     *
     * <p>线程：只在 {@link #POOL} 的线程上调用，只碰字符串，绝不触碰实体/Level。
     * URL 一律写死为 https 的两个官方域名，玩家名经白名单过滤后才拼进路径（防注入）。
     *
     * @return 皮肤 URL；查不到/网络失败/名字不合法返回 null
     */
    static String lookupSkinUrlByName(String name) {
        if (name == null || !USERNAME.matcher(name).matches()) return null;
        try {
            // ① 名字 → 档案（含正版 UUID）
            HttpRequest profileReq = HttpRequest.newBuilder(URI.create(MOJANG_PROFILE_API + name))
                    .timeout(Duration.ofSeconds(8))
                    .header("User-Agent", "CCNR-PM")
                    .GET()
                    .build();
            HttpResponse<String> profileResp = HTTP.send(profileReq, HttpResponse.BodyHandlers.ofString());
            if (profileResp.statusCode() != 200) return null;
            JsonObject profile = JsonUtil.GSON.fromJson(profileResp.body(), JsonObject.class);
            if (profile == null || !profile.has("id")) return null;
            String id = profile.get("id").getAsString();
            if (id.isBlank()) return null;

            // ② 正版 UUID → 档案属性（textures 的 base64 载荷里才是皮肤 URL）
            HttpRequest skinReq = HttpRequest.newBuilder(URI.create(MOJANG_SESSION_API + id))
                    .timeout(Duration.ofSeconds(8))
                    .header("User-Agent", "CCNR-PM")
                    .GET()
                    .build();
            HttpResponse<String> skinResp = HTTP.send(skinReq, HttpResponse.BodyHandlers.ofString());
            if (skinResp.statusCode() != 200) return null;
            JsonObject info = JsonUtil.GSON.fromJson(skinResp.body(), JsonObject.class);
            if (info == null
                    || !info.has("properties")
                    || !info.get("properties").isJsonArray()) return null;
            for (JsonElement el : info.getAsJsonArray("properties")) {
                if (!el.isJsonObject()) continue;
                JsonObject prop = el.getAsJsonObject();
                if (!prop.has("name") || !"textures".equals(prop.get("name").getAsString())) continue;
                String value = prop.has("value") ? prop.get("value").getAsString() : "";
                Optional<String> url = SkinUrl.fromTexturesProperty(value);
                if (url.isPresent()) return url.get();
            }
            return null;
        } catch (Exception e) {
            // 网络异常按「这次查不到」处理：调用方会记负缓存，不再反复重试
            return null;
        }
    }

    /** 下载 PNG；只接受 http/https 与真正的 PNG（防止把错误页当皮肤存进缓存）。 */
    private static byte[] download(String url) throws Exception {
        // 协议白名单复用 SkinUrl：外部数据里的 URL 绝不能变成读本机文件的通道
        if (!SkinUrl.isHttpUrl(url)) return null;
        URI uri = URI.create(url);
        HttpRequest req = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(8))
                // 部分皮肤站会按 UA 拒绝空 UA 的请求
                .header("User-Agent", "CCNR-PM")
                .GET()
                .build();
        HttpResponse<byte[]> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofByteArray());
        if (resp.statusCode() != 200) return null;
        byte[] body = resp.body();
        if (body == null || body.length < 8 || body.length > MAX_PNG_BYTES) return null;
        // PNG 魔数
        if ((body[0] & 0xFF) != 0x89 || body[1] != 'P' || body[2] != 'N' || body[3] != 'G') return null;
        return body;
    }

    /**
     * 写入缓存并触发（节流后的）落盘。
     *
     * <p>包级可见而不是 private：这是「缓存 + 落盘 + 重载」这条链路的写入端，
     * 而该链路正是「重启后头像全变默认皮肤」那次缺陷的所在，
     * 必须能被 {@code AvatarServiceTest} 真跑一遍（起服务验证已被禁止）。
     */
    static void put(UUID uuid, String name, String base64) {
        CACHE.put(uuid, new Entry(name, System.currentTimeMillis(), base64));
        evictIfNeeded();
        dirty = true;
        save(false);
    }

    /** 超出容量时淘汰最旧的（够用即可；不做 LRU 访问计数，成本不值）。 */
    private static void evictIfNeeded() {
        if (CACHE.size() <= MAX_ENTRIES) return;
        List<Map.Entry<UUID, Entry>> sorted = new ArrayList<>(CACHE.entrySet());
        sorted.sort(Comparator.comparingLong(e -> e.getValue().fetchedAt()));
        int remove = CACHE.size() - MAX_ENTRIES;
        for (int i = 0; i < remove && i < sorted.size(); i++) {
            CACHE.remove(sorted.get(i).getKey());
        }
    }

    /** 落盘（{@code force=false} 时受节流限制）。原子写，避免中途退出写坏缓存。 */
    private static void save(boolean force) {
        Path f = file;
        if (f == null || !dirty) return;
        long now = System.currentTimeMillis();
        if (!force && now - lastWriteMs < WRITE_THROTTLE_MS) return;

        JsonObject root = new JsonObject();
        root.addProperty("version", 1);
        JsonObject avatars = new JsonObject();
        for (Map.Entry<UUID, Entry> e : CACHE.entrySet()) {
            Entry v = e.getValue();
            JsonObject o = new JsonObject();
            o.addProperty("name", v.name());
            o.addProperty("fetchedAt", v.fetchedAt());
            o.addProperty("png", v.base64());
            avatars.add(e.getKey().toString(), o);
        }
        root.add("avatars", avatars);
        if (JsonUtil.atomicWrite(f, root)) {
            lastWriteMs = now;
            dirty = false;
        }
    }

    /** 诊断用：把缓存概览写成若干行（{@code /pm status} 使用）。 */
    public static List<String> describe() {
        List<String> out = new ArrayList<>();
        out.add("entries=" + CACHE.size() + "/" + MAX_ENTRIES);
        out.add("inflight=" + IN_FLIGHT.size() + " negative=" + NEGATIVE.size());
        out.add("file=" + (file == null ? "?" : file.toString()) + " exists="
                + (file != null && Files.isRegularFile(file)));
        return out;
    }
}
