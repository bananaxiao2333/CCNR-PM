/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.pm.avatar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code textures} 属性解析测试。
 *
 * <p>输入来自外部（正版皮肤库 / 第三方皮肤站 / 离线服务器），格式有无数种坏法。
 * 这里把「坏输入必须降级为没有头像，而不是抛异常或去请求本机文件」钉死。
 */
class SkinUrlTest {

    private static String encode(String json) {
        return Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    private static String wrap(String skinUrl) {
        return encode(
                "{\"timestamp\":123,\"profileId\":\"abc\",\"textures\":{\"SKIN\":{\"url\":\"" + skinUrl + "\"}}}");
    }

    @Test
    @DisplayName("正常属性可以解析出皮肤 URL")
    void parsesValidProperty() {
        String url = "https://textures.example.com/skin/abc123";
        assertEquals(url, SkinUrl.fromTexturesProperty(wrap(url)).orElseThrow());
    }

    @Test
    @DisplayName("第三方皮肤站的 http 地址同样接受（离线服常见）")
    void acceptsPlainHttp() {
        String url = "http://skin.example.cn/data/abc.png";
        assertEquals(url, SkinUrl.fromTexturesProperty(wrap(url)).orElseThrow());
    }

    @Test
    @DisplayName("缺少 textures / SKIN / url 时返回空而不是抛异常")
    void missingFieldsYieldEmpty() {
        assertTrue(SkinUrl.fromTexturesProperty(encode("{}")).isEmpty());
        assertTrue(SkinUrl.fromTexturesProperty(encode("{\"textures\":{}}")).isEmpty());
        assertTrue(SkinUrl.fromTexturesProperty(encode("{\"textures\":{\"SKIN\":{}}}"))
                .isEmpty());
        assertTrue(SkinUrl.fromTexturesProperty(encode("{\"textures\":{\"SKIN\":{\"url\":\"\"}}}"))
                .isEmpty());
        // CAPE 有、SKIN 没有
        assertTrue(SkinUrl.fromTexturesProperty(encode("{\"textures\":{\"CAPE\":{\"url\":\"https://x/c.png\"}}}"))
                .isEmpty());
    }

    @Test
    @DisplayName("空值与非 base64 输入返回空（离线玩家没有该属性，属常态）")
    void blankAndGarbageYieldEmpty() {
        assertTrue(SkinUrl.fromTexturesProperty(null).isEmpty());
        assertTrue(SkinUrl.fromTexturesProperty("").isEmpty());
        assertTrue(SkinUrl.fromTexturesProperty("   ").isEmpty());
        assertTrue(SkinUrl.fromTexturesProperty("!!! not base64 !!!").isEmpty());
        // 是合法 base64，但内容不是 JSON
        assertTrue(SkinUrl.fromTexturesProperty(encode("hello world")).isEmpty());
    }

    @Test
    @DisplayName("非 http/https 协议一律拒绝（防止服务端被诱导去读本机文件）")
    void rejectsNonHttpSchemes() {
        assertTrue(SkinUrl.fromTexturesProperty(wrap("file:///etc/passwd")).isEmpty());
        assertTrue(SkinUrl.fromTexturesProperty(wrap("jar:file:///tmp/x.jar!/a.png"))
                .isEmpty());
        assertFalse(SkinUrl.isHttpUrl("file:///etc/passwd"));
        assertFalse(SkinUrl.isHttpUrl("jar:file:///x.jar!/a"));
        assertFalse(SkinUrl.isHttpUrl("ftp://host/a.png"));
        assertFalse(SkinUrl.isHttpUrl(null));
        assertFalse(SkinUrl.isHttpUrl(""));
        assertFalse(SkinUrl.isHttpUrl("not a url at all"));
    }

    @Test
    @DisplayName("协议大小写不敏感")
    void schemeCaseInsensitive() {
        assertTrue(SkinUrl.isHttpUrl("HTTPS://example.com/a.png"));
        assertTrue(SkinUrl.isHttpUrl("Http://example.com/a.png"));
    }

    @Test
    @DisplayName("属性前后带空白也能解析（配置文件/接口有时会带换行）")
    void toleratesSurroundingWhitespace() {
        String url = "https://example.com/s.png";
        assertEquals(url, SkinUrl.fromTexturesProperty("  " + wrap(url) + "\n").orElseThrow());
    }
}
