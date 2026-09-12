/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.pm.avatar;

import com.ccnrcom.pm.util.JsonUtil;
import com.google.gson.JsonObject;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;

/**
 * 从玩家的 {@code textures} 属性里解析出皮肤图片 URL（纯逻辑，无 MC 依赖，可直接单测）。
 *
 * <p>这个属性长这样：一段 **base64 编码的 JSON**，里面再套一层 {@code textures.SKIN.url}：
 *
 * <pre>
 * eyJ0aW1lc3RhbXAiOi4uLiwidGV4dHVyZXMiOnsiU0tJTiI6eyJ1cmwiOiJodHRwczovL2V4YW1wbGUuY29tL3NraW4ucG5nIn19fQ==
 *   → {"timestamp":..., "textures":{"SKIN":{"url":"https://example.com/skin.png"}}}
 * </pre>
 *
 * <p>把它单独抽出来的理由：解析路径有两层（base64 → JSON → 嵌套字段），
 * 且输入来自外部（皮肤站/正版），**任何一层格式不对都必须优雅降级为「没有头像」而不是抛异常**。
 * 这类「坏输入下的行为」正适合用单测钉死。
 */
public final class SkinUrl {

    private SkinUrl() {}

    /**
     * 解析皮肤 URL。
     *
     * @param texturesProperty {@code GameProfile} 里 {@code textures} 属性的值（base64）
     * @return 可用的 http/https URL；任何异常情形（空、非 base64、非 JSON、缺字段、协议不对）都返回空
     */
    public static Optional<String> fromTexturesProperty(String texturesProperty) {
        if (texturesProperty == null || texturesProperty.isBlank()) return Optional.empty();
        try {
            String json = new String(Base64.getDecoder().decode(texturesProperty.trim()), StandardCharsets.UTF_8);
            JsonObject root = JsonUtil.GSON.fromJson(json, JsonObject.class);
            if (root == null || !root.has("textures") || !root.get("textures").isJsonObject()) {
                return Optional.empty();
            }
            JsonObject textures = root.getAsJsonObject("textures");
            if (!textures.has("SKIN") || !textures.get("SKIN").isJsonObject()) {
                return Optional.empty();
            }
            String url = JsonUtil.str(textures.getAsJsonObject("SKIN"), "url", "");
            if (url.isBlank() || !isHttpUrl(url)) return Optional.empty();
            return Optional.of(url);
        } catch (Exception e) {
            // 坏输入不是错误路径而是常态（离线玩家就没有这个属性），一律降级为「无头像」
            return Optional.empty();
        }
    }

    /**
     * 是否是可安全请求的 http/https URL。
     *
     * <p>只放行这两种协议：皮肤 URL 来自外部数据，若不加限制，一个 {@code file:} 或
     * {@code jar:} URL 就能让服务端去读本机文件——这是个真实存在的攻击面。
     */
    public static boolean isHttpUrl(String url) {
        if (url == null || url.isBlank()) return false;
        try {
            String scheme = URI.create(url.trim()).getScheme();
            if (scheme == null) return false;
            return scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https");
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
