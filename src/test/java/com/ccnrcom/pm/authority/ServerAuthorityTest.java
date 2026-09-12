/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.pm.authority;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ccnrcom.pm.network.PmPackets;
import com.ccnrcom.pm.ticket.CategoryRegistry;
import com.ccnrcom.pm.ticket.ReportDraft;
import com.ccnrcom.pm.ticket.ReportValidator;
import com.ccnrcom.pm.ticket.SubmitGate;
import com.ccnrcom.pm.ticket.TicketCategory;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 服务端权威（server authority）的可证明版本。
 *
 * <p>「服务端是权威」如果只写在文档里，它迟早会被一次图省事的改动破坏掉——最典型的是
 * 「客户端已经校验过了，服务端就跳过吧」。本类把这条约定变成**会失败的断言**：
 *
 * <ol>
 *   <li><b>被改造过的客户端送来的草稿必须被拒</b>：面板 UI 永远产不出的输入
 *       （几百个关联玩家、十万字正文、停用/不存在的类别……）在服务端校验里必须一律不通过。
 *       等价于声明：Web/UI 的约束**不是**安全边界，服务端校验才是。</li>
 *   <li><b>C2S 包不得携带身份字段</b>：举报人身份只能来自连接（{@code ctx.getSender()}），
 *       一旦包里出现 {@code reporterName}/{@code reporterUuid} 这类字段，
 *       就意味着服务端开始「相信客户端自称是谁」——那是权威坍塌的开始。</li>
 *   <li><b>防刷闸门是纯服务端概念</b>：冷却与并发上限在客户端面板里根本不存在，
 *       因此不可能有「客户端已校验」的借口（边界由 {@code SubmitGateTest} 钉死）。</li>
 * </ol>
 *
 * <p>这些断言全是**行为/结构**层面的，不依赖源码文本匹配，因此不会因为格式调整而误报。
 */
class ServerAuthorityTest {

    /** 服务端校验用的类别表（与运行时同一套构造方式）。 */
    private static final CategoryRegistry REGISTRY = new CategoryRegistry(CategoryRegistry.defaults());

    private static final ReportValidator.Limits LIMITS = new ReportValidator.Limits(1, 256, 512);

    private static List<String> reject(ReportDraft draft) {
        return ReportValidator.validate(draft, REGISTRY, LIMITS).stream()
                .map(ReportValidator.Error::key)
                .toList();
    }

    // ------------------------------------------------------------------
    // 1) 被改造过的客户端：UI 产不出的输入，服务端必须一律拒绝
    // ------------------------------------------------------------------

    @Test
    @DisplayName("超量关联玩家被拒（UI 有分隔符与上限，改造过的客户端可以塞几百个）")
    void tooManyTargetsRejected() {
        List<String> names = new ArrayList<>();
        for (int i = 0; i < 500; i++) names.add("p" + i);
        List<String> errs = reject(new ReportDraft(String.join(",", names), "cheating", "内容", ""));
        assertTrue(errs.contains(ReportValidator.ERR_TOO_MANY_TARGETS), "塞 500 个关联玩家必须被拒，实际: " + errs);
    }

    @Test
    @DisplayName("超长正文被拒（客户端的 maxLength 只是输入框限制，不是边界）")
    void oversizedContentRejected() {
        String huge = "x".repeat(100_000);
        assertTrue(reject(new ReportDraft("Bob", "cheating", huge, "")).contains(ReportValidator.ERR_CONTENT_LONG));
    }

    @Test
    @DisplayName("超长详细描述被拒")
    void oversizedDetailRejected() {
        String huge = "y".repeat(50_000);
        assertTrue(reject(new ReportDraft("Bob", "cheating", "内容", huge)).contains(ReportValidator.ERR_DETAIL_LONG));
    }

    @Test
    @DisplayName("停用的类别被拒（客户端可能拿着旧的类别列表）")
    void disabledCategoryRejected() {
        CategoryRegistry onlyDisabled = new CategoryRegistry(List.of(new TicketCategory("x", null, 1, false)));
        List<String> errs =
                ReportValidator.validate(new ReportDraft("Bob", "x", "内容", ""), onlyDisabled, LIMITS).stream()
                        .map(ReportValidator.Error::key)
                        .toList();
        assertTrue(errs.contains(ReportValidator.ERR_CATEGORY));
    }

    @Test
    @DisplayName("不存在的类别被拒（客户端可以凭空编一个 id）")
    void fabricatedCategoryRejected() {
        assertTrue(reject(new ReportDraft("Bob", "totally_made_up", "内容", "")).contains(ReportValidator.ERR_CATEGORY));
    }

    @Test
    @DisplayName("空正文被拒（必填项不因客户端跳过校验而放行）")
    void blankContentRejected() {
        assertTrue(reject(new ReportDraft("Bob", "cheating", "   ", "")).contains(ReportValidator.ERR_CONTENT_EMPTY));
    }

    @Test
    @DisplayName("超大玩家名被拒（避免把超长字符串写进库）")
    void oversizedTargetNameRejected() {
        assertTrue(reject(new ReportDraft("n".repeat(500), "cheating", "内容", ""))
                .contains(ReportValidator.ERR_TARGET_NAME));
    }

    @Test
    @DisplayName("校验器不提供任何「客户端已校验」的绕过入口")
    void noClientTrustBypass() {
        // validate 的签名里没有任何 boolean/flag 参数可以跳过检查——
        // 用反射把「签名形状」本身固定下来，防止日后有人加个 skipValidation 之类的参数
        for (var m : ReportValidator.class.getDeclaredMethods()) {
            if (!m.getName().equals("validate")) continue;
            for (Class<?> t : m.getParameterTypes()) {
                assertFalse(t == boolean.class || t == Boolean.class, "validate 出现了 boolean 参数，等于给「跳过校验」开了口子: " + m);
            }
        }
    }

    // ------------------------------------------------------------------
    // 2) C2S 包不得携带身份字段
    // ------------------------------------------------------------------

    @Test
    @DisplayName("提交包只带「意图」字段，不含举报人身份（身份只能来自连接）")
    void submitPacketCarriesNoIdentity() {
        Set<String> fields = new TreeSet<>();
        for (Field f : PmPackets.SubmitTicketC2S.class.getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers())) continue;
            fields.add(f.getName());
        }
        assertEquals(
                Set.of("targetRaw", "categoryId", "content", "detail"),
                fields,
                "提交包的字段集合变了——若新增身份类字段（reporter/name/uuid），"
                        + "等于让服务端开始相信客户端自称是谁；若新增其它业务字段，"
                        + "请确认它同样在 TicketService.submit 里被重新校验");
    }

    @Test
    @DisplayName("提交包的字段里没有任何「举报人」语义（防改名绕过上面的字段集合断言）")
    void submitPacketHasNoReporterSemantics() {
        for (Field f : PmPackets.SubmitTicketC2S.class.getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers())) continue;
            String n = f.getName().toLowerCase(java.util.Locale.ROOT);
            assertFalse(
                    n.contains("reporter") || n.contains("sender") || n.contains("author") || n.equals("uuid"),
                    "提交包里出现了疑似身份字段: " + f.getName());
        }
    }

    // ------------------------------------------------------------------
    // 3) 防刷是纯服务端概念：客户端面板完全不知道它的存在
    // ------------------------------------------------------------------

    @Test
    @DisplayName("冷却与并发上限由服务端独立判定，且客户端拿不到这两个值")
    void rateLimitsAreServerOnly() {
        assertEquals(4, PmPackets.SubmitTicketC2S.class.getDeclaredFields().length, "提交包字段数变化需复核");

        // 客户端能收到的「长度上限」是有意的（用于输入框 maxLength 与本地预校验），
        // 但冷却是服务端独有：被挡的玩家只会在回包错误里第一次听说它
        Optional<ReportValidator.Error> blocked =
                SubmitGate.check(new SubmitGate.Limits(60, 0), new SubmitGate.History(1_000L, 0), 2_000L);
        assertNotNull(blocked.orElseThrow().key());
    }
}
