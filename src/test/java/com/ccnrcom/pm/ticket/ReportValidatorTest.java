/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.pm.ticket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 举报校验规则测试（客户端预校验与服务端重校验共用同一实现，因此这里的断言两边都成立）。 */
class ReportValidatorTest {

    private static final CategoryRegistry REGISTRY = new CategoryRegistry(CategoryRegistry.defaults());

    private static final ReportValidator.Limits LIMITS = new ReportValidator.Limits(1, 20, 30);

    private static List<String> keys(List<ReportValidator.Error> errors) {
        return errors.stream().map(ReportValidator.Error::key).toList();
    }

    private static ReportDraft draft(String targets, String category, String content, String detail) {
        return new ReportDraft(targets, category, content, detail);
    }

    @Test
    @DisplayName("完整合法的举报通过校验")
    void validDraftPasses() {
        assertTrue(ReportValidator.validate(draft("Bob", "cheating", "他用了透视", "在矿洞里隔墙挖矿"), REGISTRY, LIMITS)
                .isEmpty());
    }

    @Test
    @DisplayName("关联玩家与详细描述都选填：两者留空也能提交")
    void targetsAndDetailAreOptional() {
        List<ReportValidator.Error> errors =
                ReportValidator.validate(draft("", "cheating", "有人在刷屏", ""), REGISTRY, LIMITS);
        assertTrue(errors.isEmpty(), "只填举报内容与类别就应当可以提交，实际: " + keys(errors));
    }

    @Test
    @DisplayName("只有一个空格也算未填写，不触发关联玩家相关错误")
    void blankTargetsIsTreatedAsEmpty() {
        assertTrue(ReportValidator.validate(draft("   ", "cheating", "内容", ""), REGISTRY, LIMITS)
                .isEmpty());
    }

    @Test
    @DisplayName("未启用或不存在的类别会被拒绝（类别仍然是必填）")
    void unknownCategoryRejected() {
        assertEquals(
                List.of(ReportValidator.ERR_CATEGORY),
                keys(ReportValidator.validate(draft("Bob", "not_a_category", "内容", ""), REGISTRY, LIMITS)));
    }

    @Test
    @DisplayName("停用的类别不可再被选择")
    void disabledCategoryRejected() {
        CategoryRegistry onlyDisabled = new CategoryRegistry(List.of(new TicketCategory("x", null, 1, false)));
        assertEquals(
                List.of(ReportValidator.ERR_CATEGORY),
                keys(ReportValidator.validate(draft("Bob", "x", "内容", ""), onlyDisabled, LIMITS)));
    }

    @Test
    @DisplayName("空的举报内容会被拒绝（内容仍然是必填）")
    void emptyContentRejected() {
        assertEquals(
                List.of(ReportValidator.ERR_CONTENT_EMPTY),
                keys(ReportValidator.validate(draft("Bob", "cheating", "   ", ""), REGISTRY, LIMITS)));
    }

    @Test
    @DisplayName("超长内容与超长描述带上限参数返回")
    void lengthLimitsEnforced() {
        List<ReportValidator.Error> errors =
                ReportValidator.validate(draft("Bob", "cheating", "x".repeat(21), "y".repeat(31)), REGISTRY, LIMITS);
        assertEquals(List.of(ReportValidator.ERR_CONTENT_LONG, ReportValidator.ERR_DETAIL_LONG), keys(errors));
        assertEquals(List.of("20"), errors.get(0).args());
        assertEquals(List.of("30"), errors.get(1).args());
    }

    @Test
    @DisplayName("关联玩家超过数量上限会被拒绝")
    void tooManyTargetsRejected() {
        String many = String.join(", ", List.of("a1", "a2", "a3", "a4", "a5", "a6", "a7", "a8", "a9"));
        List<ReportValidator.Error> errors =
                ReportValidator.validate(draft(many, "cheating", "内容", ""), REGISTRY, LIMITS);
        assertEquals(List.of(ReportValidator.ERR_TOO_MANY_TARGETS), keys(errors));
        assertEquals(
                List.of(String.valueOf(ReportValidator.MAX_TARGETS)),
                errors.get(0).args());
    }

    @Test
    @DisplayName("恰好达到上限时应当放行（边界不多不少）")
    void exactlyMaxTargetsAllowed() {
        String exactly = String.join(", ", List.of("a1", "a2", "a3", "a4", "a5", "a6", "a7", "a8"));
        assertTrue(ReportValidator.validate(draft(exactly, "cheating", "内容", ""), REGISTRY, LIMITS)
                .isEmpty());
    }

    @Test
    @DisplayName("名字含非法字符会被拒绝（用户要求校验目标是否合法：格式先挡住乱名字）")
    void malformedTargetNameRejected() {
        // 注意：空白是**分隔符**不是非法字符（"with space" 会被拆成两个名字），因此不在此列
        for (String bad : new String[] {"Bob!", "带中文", "a-b", "a.b", "a@b"}) {
            List<ReportValidator.Error> errors =
                    ReportValidator.validate(draft(bad, "cheating", "内容", ""), REGISTRY, LIMITS);
            assertEquals(List.of(ReportValidator.ERR_TARGET_NAME_FORMAT), keys(errors), "非法名字应只报格式错: " + bad);
            assertEquals(List.of(bad), errors.get(0).args(), "错误里要带上是哪个名字");
        }
    }

    @Test
    @DisplayName("短名字与下划线名字放行（离线服的短名是真实存在的，不能当非法）")
    void shortAndUnderscoreNamesAllowed() {
        for (String ok : new String[] {"a", "ab", "A_1", "_x", "Player_123456789"}) {
            assertTrue(
                    ReportValidator.validate(draft(ok, "cheating", "内容", ""), REGISTRY, LIMITS)
                            .isEmpty(),
                    "应放行: " + ok);
        }
        assertTrue(ReportValidator.isWellFormedName("Alice"));
        assertFalse(ReportValidator.isWellFormedName("Alice "));
        assertFalse(ReportValidator.isWellFormedName(null));
    }

    @Test
    @DisplayName("单个玩家名过长会被拒绝")
    void longTargetNameRejected() {
        String longName = "n".repeat(ReportValidator.MAX_TARGET_NAME + 1);
        List<ReportValidator.Error> errors =
                ReportValidator.validate(draft(longName, "cheating", "内容", ""), REGISTRY, LIMITS);
        assertEquals(List.of(ReportValidator.ERR_TARGET_NAME), keys(errors));
    }

    @Test
    @DisplayName("多个问题一次性全部返回，避免玩家改一条报一条")
    void reportsAllProblemsAtOnce() {
        List<String> got = keys(ReportValidator.validate(draft("", "bad", "", ""), REGISTRY, LIMITS));
        assertTrue(got.contains(ReportValidator.ERR_CATEGORY));
        assertTrue(got.contains(ReportValidator.ERR_CONTENT_EMPTY));
    }

    @Test
    @DisplayName("草稿构造时 trim，避免把空白当作有效内容")
    void draftIsNormalised() {
        ReportDraft d = draft("  Bob  ", " cheating ", "  内容  ", "  细节  ");
        assertEquals("Bob", d.targetRaw());
        assertEquals("cheating", d.categoryId());
        assertEquals("内容", d.content());
        assertEquals("细节", d.detail());
    }

    @Test
    @DisplayName("详细描述为空是允许的（选填字段）")
    void emptyDetailIsAllowed() {
        assertTrue(ReportValidator.validate(draft("Bob", "other", "内容", ""), REGISTRY, LIMITS)
                .isEmpty());
    }

    // ------------------------------------------------------------------
    // parseTargets：分隔符与去重
    // ------------------------------------------------------------------

    @Test
    @DisplayName("半角逗号分隔")
    void parseHalfwidthComma() {
        assertEquals(List.of("Bob", "Carol"), ReportValidator.parseTargets("Bob, Carol"));
    }

    @Test
    @DisplayName("全角逗号也能分隔（中文输入法下的常见输入）")
    void parseFullwidthComma() {
        assertEquals(List.of("Bob", "Carol"), ReportValidator.parseTargets("Bob，Carol"));
        assertEquals(List.of("阿明", "小红"), ReportValidator.parseTargets("阿明，小红"));
    }

    @Test
    @DisplayName("空白与分号都算分隔符")
    void parseWhitespaceAndSemicolons() {
        assertEquals(List.of("Bob", "Carol", "Dave"), ReportValidator.parseTargets("Bob Carol;Dave"));
        assertEquals(List.of("Bob", "Carol"), ReportValidator.parseTargets("Bob\nCarol"));
    }

    @Test
    @DisplayName("去重忽略大小写且保持输入顺序")
    void parseDeduplicates() {
        assertEquals(List.of("Bob", "Carol"), ReportValidator.parseTargets("Bob, bob, Carol, BOB"));
    }

    @Test
    @DisplayName("空输入、多余分隔符、首尾空白都得到干净结果")
    void parseHandlesNoise() {
        assertEquals(List.of(), ReportValidator.parseTargets(null));
        assertEquals(List.of(), ReportValidator.parseTargets(""));
        assertEquals(List.of(), ReportValidator.parseTargets("   "));
        assertEquals(List.of(), ReportValidator.parseTargets(",, ,，"));
        assertEquals(List.of("Bob"), ReportValidator.parseTargets("  , Bob ,  , "));
    }
}
