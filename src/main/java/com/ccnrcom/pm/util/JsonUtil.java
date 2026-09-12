/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.pm.util;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * JSON 读写工具（沿用 CCNR 系列「原子写 + 损坏留 .bak」惯例）。
 *
 * <p>为什么原子写：工单是「提交即生效」的数据，直接覆盖原文件时若进程在写入中途退出， 会同时丢掉旧数据与不完整的新数据。先写同目录 .tmp 再 ATOMIC_MOVE 覆盖，
 * 保证任何时刻磁盘上的文件要么是旧的完整内容、要么是新的完整内容。
 *
 * <p>边界：{@code ATOMIC_MOVE} 在同一文件系统内保证原子；跨文件系统会抛异常，因此 .tmp 必须与目标文件同目录（这里用
 * {@code resolveSibling}）。失败时保留原文件不动，由调用方决定是否降级。
 */
public final class JsonUtil {

    private static final Logger LOGGER = LogManager.getLogger("ccnr_pm");

    public static final Gson GSON =
            new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private JsonUtil() {}

    /** 原子写：先写 {@code <file>.tmp}，再移动覆盖；失败时原文件保持不动。 */
    public static boolean atomicWrite(Path file, JsonElement element) {
        if (file == null) return false;
        try {
            Path parent = file.getParent();
            if (parent != null) Files.createDirectories(parent);
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, GSON.toJson(element) + System.lineSeparator(), StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            return true;
        } catch (IOException e) {
            LOGGER.error("[CCNR-PM] 写入失败: {} —— {}", file, e.toString());
            return false;
        }
    }

    /** 读取 JSON 对象；文件缺失返回空；解析失败备份 .bak 并返回空（服务继续，不因单文件损坏停摆）。 */
    public static Optional<JsonObject> readObject(Path file) {
        if (file == null || !Files.isRegularFile(file)) return Optional.empty();
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            JsonElement el = GSON.fromJson(r, JsonElement.class);
            if (el == null || !el.isJsonObject()) throw new JsonParseException("根节点不是 JSON 对象");
            return Optional.of(el.getAsJsonObject());
        } catch (Exception e) {
            LOGGER.error("[CCNR-PM] 读取失败: {} —— {}", file, e.toString());
            backup(file);
            return Optional.empty();
        }
    }

    /** 从 classpath 资源读取 JSON 对象（用于默认配置模板）。 */
    public static Optional<JsonObject> readResource(String path) {
        try (InputStream in = JsonUtil.class.getResourceAsStream(path)) {
            if (in == null) return Optional.empty();
            try (Reader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                JsonElement el = GSON.fromJson(r, JsonElement.class);
                if (el == null || !el.isJsonObject()) return Optional.empty();
                return Optional.of(el.getAsJsonObject());
            }
        } catch (Exception e) {
            LOGGER.error("[CCNR-PM] 读取资源失败: {} —— {}", path, e.toString());
            return Optional.empty();
        }
    }

    /** 损坏文件备份为 {@code <file>.bak}（保留原文件，便于人工比对）。 */
    private static void backup(Path file) {
        try {
            Files.copy(file, file.resolveSibling(file.getFileName() + ".bak"), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            LOGGER.error("[CCNR-PM] 备份失败: {} —— {}", file, e.toString());
        }
    }

    /** 取字符串字段，缺失/非字符串返回默认值。 */
    public static String str(JsonObject o, String key, String def) {
        if (o == null || !o.has(key) || o.get(key).isJsonNull()) return def;
        try {
            return o.get(key).getAsString();
        } catch (Exception e) {
            return def;
        }
    }

    /** 取整数字段，缺失/非数字返回默认值。 */
    public static long num(JsonObject o, String key, long def) {
        if (o == null || !o.has(key) || o.get(key).isJsonNull()) return def;
        try {
            return o.get(key).getAsLong();
        } catch (Exception e) {
            return def;
        }
    }

    /** 取布尔字段，缺失/非布尔返回默认值。 */
    public static boolean bool(JsonObject o, String key, boolean def) {
        if (o == null || !o.has(key) || o.get(key).isJsonNull()) return def;
        try {
            return o.get(key).getAsBoolean();
        } catch (Exception e) {
            return def;
        }
    }
}
