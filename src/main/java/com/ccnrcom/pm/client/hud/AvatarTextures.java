/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.pm.client.hud;

import com.mojang.blaze3d.platform.NativeImage;
import java.io.ByteArrayInputStream;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * 客户端头像贴图缓存：把服务端下发的 base64 皮肤 PNG 注册成可绘制的贴图。
 *
 * <p>为什么需要它：服务端发来的是**字节**，而渲染需要的是一个 {@link ResourceLocation}。
 * 中间这一步（解码 PNG → {@link DynamicTexture} → 注册进 {@code TextureManager}）必须做一次。
 *
 * <p>**对称清理**：注册进 TextureManager 的贴图是 GPU 资源，不释放就会随通知累积泄漏。
 * 因此这里按「内容指纹」缓存、并在 {@link #releaseAll()} 时逐个 {@code release} + {@code close}。
 * 缓存键带上内容哈希，服务端换了头像（新覆盖旧）时会生成新条目，旧的自然被释放。
 */
public final class AvatarTextures {

    private static final Logger LOGGER = LogManager.getLogger("ccnr_pm");

    /** 一次最多缓存多少张头像贴图；超出后整批释放重建（通知卡片场景下数量很小）。 */
    private static final int MAX_TEXTURES = 64;

    /** 缓存：内容键 → 已注册的贴图。 */
    private static final Map<String, Entry> CACHE = new HashMap<>();

    private AvatarTextures() {}

    /**
     * 一张已注册的头像贴图。
     *
     * @param location 贴图位置（{@code blit} 用）
     * @param width 贴图宽（皮肤通常 64）
     * @param height 贴图高（新版 64，旧版 32 —— UV 计算必须用真实高度，否则旧皮肤会画歪）
     */
    public record Entry(ResourceLocation location, int width, int height) {}

    /**
     * 取（必要时创建）一张头像贴图。
     *
     * @param key 内容键（调用方用 uuid + 内容指纹拼接，保证换了头像会重建）
     * @param base64 皮肤 PNG 的 base64
     * @return 贴图；解码失败返回 {@code null}（调用方应回退到本地皮肤）
     */
    public static Entry get(String key, String base64) {
        Entry cached = CACHE.get(key);
        if (cached != null) return cached;

        NativeImage image = null;
        try {
            byte[] bytes = Base64.getDecoder().decode(base64);
            image = NativeImage.read(new ByteArrayInputStream(bytes));
            int w = image.getWidth();
            int h = image.getHeight();
            // 只接受合法皮肤尺寸；其它尺寸画出来的脸一定是错的
            if (w != 64 || (h != 64 && h != 32)) {
                LOGGER.warn("[CCNR-PM] 忽略尺寸异常的头像贴图: {}x{}", w, h);
                image.close();
                return null;
            }
            DynamicTexture texture = new DynamicTexture(image);
            ResourceLocation loc = new ResourceLocation("ccnr_pm", "avatar/" + sanitize(key));
            Minecraft.getInstance().getTextureManager().register(loc, texture);
            Entry entry = new Entry(loc, w, h);
            if (CACHE.size() >= MAX_TEXTURES) releaseAll();
            CACHE.put(key, entry);
            return entry;
        } catch (Exception e) {
            if (image != null) image.close();
            LOGGER.warn("[CCNR-PM] 头像贴图解码失败，将回退到本地皮肤: {}", e.toString());
            return null;
        }
    }

    /** 释放全部头像贴图（离开世界、卡片清空时调用）。 */
    public static void releaseAll() {
        if (CACHE.isEmpty()) return;
        for (Entry e : CACHE.values()) {
            try {
                Minecraft.getInstance().getTextureManager().release(e.location());
            } catch (Exception ex) {
                LOGGER.warn("[CCNR-PM] 释放头像贴图失败: {}", ex.toString());
            }
        }
        CACHE.clear();
    }

    public static int size() {
        return CACHE.size();
    }

    /** 贴图路径只能包含安全字符（ResourceLocation 不允许任意字符）。 */
    private static String sanitize(String key) {
        StringBuilder sb = new StringBuilder(key.length());
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            sb.append((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || c == '/' || c == '.' ? c : '_');
        }
        return sb.toString();
    }
}
