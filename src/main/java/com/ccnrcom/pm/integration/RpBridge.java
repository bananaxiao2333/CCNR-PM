/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.pm.integration;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.server.level.ServerPlayer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * CCNR-RP 软依赖桥（**服务端**，反射实现，无编译期依赖）。
 *
 * <h2>为什么是反射</h2>
 * 本模组与 CCNR-RP 是互相独立的仓库（AGENTS 明令禁止跨仓库引用），但用户要求
 * 「装了 CCNR-RP 就按它的角色刷人、把它的人送回阴间」。这正是软依赖的语义：
 * **装了就用对方的实现，没装就退回原版行为**（刷出＝冒险模式，送回阴间＝旁观模式）。
 * 因此这里按类名与方法名反射调用对方的公开入口，任何一步失败都降级为「不可用」，
 * 绝不影响本模组自身功能。客户端侧的同类做法见 {@code client.RpPanel}。
 *
 * <h2>为什么不重复实现对方的校验</h2>
 * 「刷出玩家」走对方**唯一**的部署入口 {@code SpawnFramework.deploy(...)}（带 FORCE_DEPLOY
 * 与 LIMIT_SKIP），而不是在这里自己清背包、发装备、传送、写状态——那等于把对方的部署流程抄一份，
 * 对方一改就会漂移，症状是「用 PM 刷出来的人少了一件装备」这类极难归属的缺陷。
 * 同理，「送回阴间」走对方唯一的退场核心 {@code StatusManager.retire(...)}（SKIP_SETTLE，
 * 不生成遗体），清背包与卸属性都由对方那一条路径负责（docs/05 的观察者契约）。
 *
 * <h2>缓存</h2>
 * 「装没装」在进程生命周期内不会变（模组加载是启动期确定的），因此结果缓存，
 * 避免每次动作都走一次 {@code Class.forName}。取不到实例（未初始化完成）时**不缓存失败**，
 * 因为那可能只是时序问题。
 */
public final class RpBridge {

    private static final Logger LOGGER = LogManager.getLogger("ccnr_pm");

    /** CCNR-RP 的入口类（其静态字段持有各服务实例）。 */
    private static final String MOD_CLASS = "com.ccnrcom.rp.CCNRRPMod";

    private static final String FLAG_DEPLOY = "com.ccnrcom.rp.spawn.DeployFlag";

    private static final String FLAG_RETIRE = "com.ccnrcom.rp.status.RetireFlag";

    private static final String STATUS_MANAGER = "com.ccnrcom.rp.status.StatusManager";

    private static final String FACTION_PROFESSIONS = "com.ccnrcom.rp.faction.FactionProfessions";

    private static final String JSON_OBJECT = "com.google.gson.JsonObject";

    /** 「装没装」的缓存：null 表示还没判断过。 */
    private static volatile Boolean present;

    private RpBridge() {}

    /** CCNR-RP 是否存在（类加载成功即认为存在；结果缓存）。 */
    public static boolean available() {
        Boolean cached = present;
        if (cached != null) return cached;
        try {
            Class.forName(MOD_CLASS);
            present = Boolean.TRUE;
        } catch (Throwable t) {
            present = Boolean.FALSE;
        }
        return present;
    }

    /**
     * 可选角色（职业）列表：id + 显示名。没装 RP、或对方还没初始化完时返回空列表。
     *
     * <p>顺序沿用对方配置里的顺序（与它自己的管理面板一致），不做二次排序——
     * 两处排序规则不一致会让人找不到东西。
     */
    public static List<ProfessionRef> professions() {
        List<ProfessionRef> out = new ArrayList<>();
        if (!available()) return out;
        try {
            Object factions = staticField("factions");
            if (factions == null) return out;
            Object idsObj = factions.getClass().getMethod("professionIds").invoke(factions);
            if (!(idsObj instanceof List<?> ids)) return out;
            for (Object raw : ids) {
                if (!(raw instanceof String id) || id.isBlank()) continue;
                out.add(new ProfessionRef(id, displayName(factions, id)));
            }
        } catch (Throwable t) {
            LOGGER.warn("[CCNR-PM] 读取 CCNR-RP 职业列表失败（角色选择将不可用）: {}", t.toString());
            return new ArrayList<>();
        }
        return out;
    }

    /** 职业显示名：优先用对方的 {@code idsSafeName(def)}，失败时退回 JSON 的 name/id。 */
    private static String displayName(Object factions, String professionId) {
        try {
            Object defObj = factions.getClass()
                    .getMethod("findProfession", String.class)
                    .invoke(factions, professionId);
            if (!(defObj instanceof Optional<?> opt) || opt.isEmpty()) return professionId;
            Object def = opt.get();
            try {
                Class<?> jsonCls = Class.forName(JSON_OBJECT);
                Method safeName = Class.forName(FACTION_PROFESSIONS).getMethod("idsSafeName", jsonCls);
                Object name = safeName.invoke(null, def);
                if (name instanceof String s && !s.isBlank()) return s;
            } catch (Throwable ignored) {
                // 对方改了签名：退回直接读 JSON 字段
            }
            if (def instanceof JsonElement el && el.isJsonObject()) {
                JsonObject o = el.getAsJsonObject();
                for (String key : new String[] {"name", "id"}) {
                    if (o.has(key) && o.get(key).isJsonPrimitive()) {
                        String s = o.get(key).getAsString();
                        if (!s.isBlank()) return s;
                    }
                }
            }
        } catch (Throwable ignored) {
            // 名字取不到不是致命问题：id 本身就能显示与选择
        }
        return professionId;
    }

    /**
     * 强制把玩家刷成指定角色（**无视对方的冷却与等级校验**，用户明确要求）。
     *
     * <p>走对方唯一的部署入口 {@code SpawnFramework.deploy(player, professionId, wave, flags)}，flags 为：
     *
     * <ul>
     *   <li>{@code FORCE_DEPLOY}——跳过「已有在场身份」守卫（连刷两次不会互相顶掉）；</li>
     *   <li>{@code LIMIT_SKIP}——跳过在职人数上限（管理员刷人是系统强制操作，不是玩家自部署）；</li>
     *   <li>{@code SKIP_CINEMATIC}——**从 PM 面板刷人时不播入场演出**（用户明确要求）。
     *       对方在 {@code cinematic=false} 时：不拼装演出载荷、不发 {@code CinematicS2C}、
     *       不播 CMDCam 场景，且**开局直接落位切生存**（不进旁观等待）。
     *       那几件事正对应「开场全屏黑 / 左下角电影 HUD / 场景动画」，
     *       一个 flag 全覆盖，而且**不用去改对方的任何配置**——玩家自己部署时的演出完全不受影响；</li>
     *   <li>{@code NO_MUSIC}——入场音乐置空。SKIP_CINEMATIC 之下音乐本就不会下发，
     *       留它是防对方日后把音乐挪到电影之外：我们的意图是「刷人不出声」，不随对方重构而失效。</li>
     * </ul>
     *
     * <p>注意仍然会走对方的**素材同步检查**：对方客户端还没同步完素材时部署会失败并给对方玩家发提示，
     * 这是对方的设计（强行部署会让客户端显示错误的装备），因此这里不绕过它，只把失败如实回报管理员。
     *
     * @return 是否部署成功
     */
    public static boolean forceDeploy(ServerPlayer target, String professionId) {
        if (target == null || professionId == null || professionId.isBlank() || !available()) return false;
        try {
            Object spawn = staticField("spawnFramework");
            if (spawn == null) return false;
            Object wave = spawn.getClass().getMethod("defaultSelfWave").invoke(spawn);
            if (wave == null) return false;
            Object flags = flagSet(FLAG_DEPLOY, "FORCE_DEPLOY", "LIMIT_SKIP", "SKIP_CINEMATIC", "NO_MUSIC");
            if (flags == null) return false;

            for (Method m : spawn.getClass().getMethods()) {
                if (!m.getName().equals("deploy") || m.getParameterCount() != 4) continue;
                Class<?>[] p = m.getParameterTypes();
                if (!p[0].isInstance(target)
                        || p[1] != String.class
                        || !p[2].isInstance(wave)
                        || !p[3].isInstance(flags)) {
                    continue;
                }
                Object result = m.invoke(spawn, target, professionId, wave, flags);
                return result instanceof Boolean ok && ok;
            }
            LOGGER.warn("[CCNR-PM] CCNR-RP 的部署入口签名不匹配，无法强制刷人");
            return false;
        } catch (Throwable t) {
            LOGGER.warn("[CCNR-PM] 强制刷人失败: {}", t.toString());
            return false;
        }
    }

    /**
     * 把玩家送回阴间：走对方唯一的退场核心 {@code StatusManager.retire(uuid, player, "retire", {SKIP_SETTLE})}。
     *
     * <p>语义与对方的「下班/退役」一致：状态转观察者（OBSERVING）+ 加复活冷却 +
     * **清背包并卸下阵营属性加成**（对方的观察者契约，docs/05）+ 不生成遗体 + 不结算经验。
     * 观察者在对方那侧会被轮询强制切旁观模式；本模组随后**立即**再切一次旁观，
     * 免得管理员等两秒才看到人变透明（最终状态一致，不是第二套规则）。
     *
     * @return 是否成功（对方未装、未初始化、或反射失败时为 false）
     */
    public static boolean retireToObserver(ServerPlayer target) {
        if (target == null || !available()) return false;
        try {
            Object flags = flagSet(FLAG_RETIRE, "SKIP_SETTLE");
            if (flags == null) return false;
            Class<?> smCls = Class.forName(STATUS_MANAGER);
            Method retire =
                    smCls.getMethod("retire", String.class, ServerPlayer.class, String.class, java.util.Set.class);
            retire.invoke(null, target.getUUID().toString(), target, "retire", flags);
            return true;
        } catch (Throwable t) {
            LOGGER.warn("[CCNR-PM] 送回阴间（CCNR-RP 退场）失败: {}", t.toString());
            return false;
        }
    }

    // ------------------------------------------------------------------
    // 反射细节
    // ------------------------------------------------------------------

    /** 读 {@code CCNRRPMod} 的公开静态字段（各服务实例）。 */
    private static Object staticField(String name) throws Exception {
        Class<?> cls = Class.forName(MOD_CLASS);
        Field f = cls.getField(name);
        return f.get(null);
    }

    /**
     * 构造对方枚举的 flag 集合（调它自己的 {@code of(...)}，而不是自己 new EnumSet）。
     *
     * <p>用对方的工厂方法是因为「集合语义」也属于对方的实现细节：它哪天改成不可变集合或加了默认项，
     * 我们跟着走就不会错。
     */
    private static Object flagSet(String enumClassName, String... names) {
        try {
            Class<?> enumCls = Class.forName(enumClassName);
            if (!enumCls.isEnum()) return null;
            Object[] arr = (Object[]) Array.newInstance(enumCls, names.length);
            for (int i = 0; i < names.length; i++) {
                arr[i] = enumConstant(enumCls, names[i]);
            }
            Method of = enumCls.getMethod("of", arr.getClass());
            return of.invoke(null, new Object[] {arr});
        } catch (Throwable t) {
            LOGGER.warn("[CCNR-PM] 构造 {} 的 flag 集合失败: {}", enumClassName, t.toString());
            return null;
        }
    }

    /** 按名字取枚举常量（不走 {@code Enum.valueOf} 的泛型体操，避免 raw/unchecked 警告）。 */
    private static Object enumConstant(Class<?> enumCls, String name) {
        for (Object constant : enumCls.getEnumConstants()) {
            if (constant instanceof Enum<?> e && e.name().equals(name)) return constant;
        }
        throw new IllegalArgumentException("枚举 " + enumCls.getName() + " 没有常量 " + name);
    }
}
