/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.pm.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 网络包注册一致性门禁。
 *
 * <p>**为什么需要它**：项目禁止用启动服务器的方式验证（见 AGENTS 硬性禁令），
 * 而「新增了一个包却忘了在 {@code PmChannel.register()} 里注册」这类错误，
 * 恰恰只有把游戏跑起来发包时才会暴露（表现为「点了没反应」）。
 * 既然不能起服务，就必须把它变成静态可查的东西。
 *
 * <p>本测试做三件事：
 * <ol>
 *   <li>{@code PmPackets} 里声明的每个包类，必须在 {@code PmChannel} 里出现且仅出现一次</li>
 *   <li>{@code PmChannel} 里注册的每个类，必须确实存在于 {@code PmPackets}</li>
 *   <li>每个包类都必须具备编解码与处理器三件套（构造器 / {@code encode} / {@code static handle}）——
 *       缺任何一个都会在运行期才炸，且报错信息往往指向别处</li>
 * </ol>
 *
 * <p>用源码文本解析而不是反射枚举嵌套类：包类都是 {@code public static final class}，
 * 文本匹配足够可靠；而"解析到 0 个"会让测试**直接失败**（见 {@link #packetClasses()}），
 * 因此不会静默通过。
 */
class PacketRegistrationTest {

    /** 形如 {@code public static final class XxxS2C {} }。 */
    private static final Pattern PACKET_CLASS = Pattern.compile("public static final class (\\w+)");

    /** 形如 {@code PmPackets.XxxS2C.class}。 */
    private static final Pattern REGISTERED = Pattern.compile("PmPackets\\.(\\w+)\\.class");

    private static Path projectRoot() {
        Path dir = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        for (int i = 0; i < 6 && dir != null; i++) {
            if (Files.isRegularFile(dir.resolve("gradle.properties")) && Files.isDirectory(dir.resolve("src"))) {
                return dir;
            }
            dir = dir.getParent();
        }
        return fail("找不到项目根目录：user.dir=" + System.getProperty("user.dir"));
    }

    private static String read(String relative) {
        try {
            return Files.readString(projectRoot().resolve(relative), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return fail("读取失败: " + relative + " —— " + e);
        }
    }

    private static Set<String> find(String source, Pattern pattern) {
        Set<String> out = new LinkedHashSet<>();
        Matcher m = pattern.matcher(source);
        while (m.find()) out.add(m.group(1));
        return out;
    }

    /** {@code PmPackets} 中声明的包类。 */
    private static Set<String> packetClasses() {
        Set<String> found = find(read("src/main/java/com/ccnrcom/pm/network/PmPackets.java"), PACKET_CLASS);
        // 解析不到任何包类说明正则或路径失效——必须失败，否则门禁形同虚设
        assertFalse(found.isEmpty(), "没有从 PmPackets.java 解析出任何包类，门禁本身失效了");
        return found;
    }

    /** {@code PmChannel} 中注册的包类。 */
    private static Set<String> registeredClasses() {
        Set<String> found = find(read("src/main/java/com/ccnrcom/pm/network/PmChannel.java"), REGISTERED);
        assertFalse(found.isEmpty(), "没有从 PmChannel.java 解析出任何注册项，门禁本身失效了");
        return found;
    }

    // ------------------------------------------------------------------

    @Test
    @DisplayName("每个声明的包都被注册了（漏注册＝点了没反应，且只有运行期才暴露）")
    void everyPacketIsRegistered() {
        Set<String> declared = packetClasses();
        Set<String> registered = registeredClasses();

        Set<String> missing = new TreeSet<>(declared);
        missing.removeAll(registered);
        assertTrue(missing.isEmpty(), "以下包在 PmPackets 里声明了但没在 PmChannel.register() 注册（且必须追加在末尾）：" + missing);
    }

    @Test
    @DisplayName("注册表里没有指向不存在类的项（改名/删除后忘了同步会在这里暴露）")
    void noDanglingRegistrations() {
        Set<String> declared = packetClasses();
        Set<String> registered = registeredClasses();

        Set<String> dangling = new TreeSet<>(registered);
        dangling.removeAll(declared);
        assertTrue(dangling.isEmpty(), "以下注册项在 PmPackets 里找不到对应类：" + dangling);
    }

    @Test
    @DisplayName("注册数量与声明数量一致（既不漏也不重）")
    void countsMatch() {
        assertEquals(packetClasses().size(), registeredClasses().size(), "声明与注册的数量必须一致");
    }

    @Test
    @DisplayName("每个包都有解码构造器 + encode + static handle（三件套缺一不可）")
    void everyPacketHasCodecAndHandler() {
        for (String name : packetClasses()) {
            Class<?> cls;
            try {
                cls = Class.forName("com.ccnrcom.pm.network.PmPackets$" + name);
            } catch (ClassNotFoundException e) {
                fail("找不到包类（声明了但没编译出来？）: " + name);
                return;
            }

            // 解码构造器：唯一参数为 FriendlyByteBuf
            boolean hasDecodeCtor = false;
            for (Constructor<?> c : cls.getDeclaredConstructors()) {
                Class<?>[] p = c.getParameterTypes();
                if (p.length == 1 && p[0].getName().endsWith("FriendlyByteBuf")) hasDecodeCtor = true;
            }
            assertTrue(hasDecodeCtor, name + " 缺少 FriendlyByteBuf 解码构造器");

            // encode(FriendlyByteBuf)
            boolean hasEncode = false;
            for (Method m : cls.getDeclaredMethods()) {
                Class<?>[] p = m.getParameterTypes();
                if (m.getName().equals("encode")
                        && p.length == 1
                        && p[0].getName().endsWith("FriendlyByteBuf")) {
                    hasEncode = true;
                }
            }
            assertTrue(hasEncode, name + " 缺少 encode(FriendlyByteBuf)");

            // static handle(XxxMsg, Supplier<NetworkEvent.Context>)
            boolean hasHandle = false;
            for (Method m : cls.getDeclaredMethods()) {
                if (!m.getName().equals("handle")) continue;
                if (!Modifier.isStatic(m.getModifiers())) continue;
                Class<?>[] p = m.getParameterTypes();
                if (p.length == 2 && p[0] == cls) hasHandle = true;
            }
            assertTrue(hasHandle, name + " 缺少 static handle(自身类型, Supplier<Context>)");
        }
    }

    @Test
    @DisplayName("协议版本是 \"1\"（改它会让已发布客户端整体失联）")
    void protocolVersionPinned() {
        assertEquals("1", PmChannel.PROTOCOL_VERSION);
    }

    @Test
    @DisplayName("C2S 包只有 5 个，且都在服务端做了权限与参数重校验（服务端权威的边界）")
    void clientToServerSurfaceIsSmallAndReviewed() {
        // 攻击面越小越好。这个数字变了就意味着新增了一条客户端能影响服务端的路径，
        // 必须同步更新 docs/02 §4 的权威链审计表，并在服务端重新校验。
        //
        // PlayerActionC2S 里的 targetUuid/targetName **不是身份字段**：身份永远只有连接
        // (ctx.getSender()) 里那一个；它们是「要对谁动手」的目标描述，服务端在自己的玩家列表里
        // 重新解析（解析不到就回绝），权限也在服务端重新判定（PmServerHandlers.canAdminTicket）。
        Set<String> c2s = new TreeSet<>();
        Matcher m = Pattern.compile("PmPackets\\.(\\w+)\\.class, id\\+\\+, NetworkDirection\\.PLAY_TO_SERVER")
                .matcher(read("src/main/java/com/ccnrcom/pm/network/PmChannel.java"));
        while (m.find()) c2s.add(m.group(1));

        assertEquals(
                Set.of(
                        "SubmitTicketC2S",
                        "RequestTicketsC2S",
                        "TicketActionC2S",
                        "PlayerActionC2S",
                        "RequestProfessionsC2S"),
                c2s,
                "C2S 包集合变了：新增 C2S 包必须（1）在服务端重新鉴权与重校验参数；" + "（2）更新 docs/02 §4 的权威链审计表；（3）确认它不携带身份字段");
    }

    @Test
    @DisplayName("注册顺序即协议编号：注册语句自上而下单调递增（新增只能追加在末尾）")
    void registrationsAreAppendOnlyOrdered() {
        String src = read("src/main/java/com/ccnrcom/pm/network/PmChannel.java");
        Matcher m = Pattern.compile("messageBuilder\\([^,]+,\\s*id\\+\\+").matcher(src);
        int count = 0;
        int lastIndex = -1;
        while (m.find()) {
            assertTrue(m.start() > lastIndex, "注册语句顺序乱了");
            lastIndex = m.start();
            count++;
        }
        assertEquals(packetClasses().size(), count, "每个包都应恰好有一条 id++ 注册语句");
    }

    @Test
    @DisplayName("每个玩家动作 id 在服务端都有处理分支（漏一个就是「点了没反应」，只有实机才发现）")
    void everyPlayerActionHasServerBranch() {
        // 动作 id 是字符串协议：两端共用 PmPackets 的常量，但如果服务端 switch 里漏了一个分支，
        // 编译、单测、门禁都不会响——只有管理员点下去才会发现什么都没发生。
        // 项目禁止起服务验证，因此把「客户端能发的每个动作都有服务端分支」做成静态断言。
        String packets = read("src/main/java/com/ccnrcom/pm/network/PmPackets.java");
        Matcher m = Pattern.compile("public static final String (ACT_[A-Z_]+)\\s*=\\s*\"([a-z_]+)\"")
                .matcher(packets);
        Set<String> names = new LinkedHashSet<>();
        while (m.find()) names.add(m.group(1));
        assertFalse(names.isEmpty(), "没有解析到任何动作常量，门禁失效");

        String handlers = read("src/main/java/com/ccnrcom/pm/network/PmServerHandlers.java");
        Set<String> missing = new TreeSet<>();
        for (String name : names) {
            if (!handlers.contains("PmPackets." + name)) missing.add(name);
        }
        assertTrue(missing.isEmpty(), "以下玩家动作在 PmServerHandlers 里没有处理分支（点了不会有任何反应）: " + missing);

        // 服务端分支必须有权限重校验：能打开看板不代表有权传送别人
        assertTrue(handlers.contains("canAdminTicket"), "PmServerHandlers 里找不到 canAdminTicket——玩家动作必须重新鉴权");
    }
}
