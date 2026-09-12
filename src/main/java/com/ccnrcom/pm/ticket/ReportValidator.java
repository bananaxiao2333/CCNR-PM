/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.pm.ticket;

import java.util.ArrayList;
import java.util.List;

/**
 * 举报提交的**纯校验**逻辑（无 MC 依赖、无 IO，可直接单测）。
 *
 * <p>为什么把校验单独抽出来：客户端面板会先做一次同样的校验以给出即时反馈，服务端收到包后再做一次。
 * 两处必须给出**完全一致**的判定，否则会出现「面板说可以提交、服务端却回绝」这种最难排查的联机缺陷。
 * 把规则收在一处，两边调用同一个函数即可根除这类不一致。
 *
 * <p>注意：服务端**绝不**因为客户端已经校验过就跳过这一步——客户端说什么都不算数。
 *
 * <h2>必填 / 选填</h2>
 * <ul>
 *   <li><b>必填</b>：举报内容（{@code content}）、举报类别（{@code categoryId}）</li>
 *   <li><b>选填</b>：关联玩家（{@code targetRaw}）、详细描述（{@code detail}）</li>
 * </ul>
 * 关联玩家选填是本功能的刻意取舍：玩家常常只有一句「有人刷屏」而说不出是谁，
 * 强制填写只会把人挡在门外，而这恰恰是最需要被记录下来的举报。
 */
public final class ReportValidator {

    /** 校验失败项：语言键 + 参数，便于客户端直接渲染成可读提示。 */
    public record Error(String key, List<String> args) {
        public static Error of(String key, String... args) {
            return new Error(key, List.of(args));
        }
    }

    /** 长度边界（来自 serverconfig；测试直接给字面量）。 */
    public record Limits(int minContent, int maxContent, int maxDetail) {
        public Limits {
            minContent = Math.max(1, minContent);
            maxContent = Math.max(minContent, maxContent);
            maxDetail = Math.max(0, maxDetail);
        }
    }

    /** 关联玩家数量上限。上限存在的意义是防止一次提交塞进几百个名字把工单撑爆。 */
    public static final int MAX_TARGETS = 8;

    /** 单个玩家名长度上限（与原版玩家名上限一致）。 */
    public static final int MAX_TARGET_NAME = 16;

    public static final String ERR_CATEGORY = "ccnr_pm.report.err.category";
    public static final String ERR_CONTENT_EMPTY = "ccnr_pm.report.err.content_empty";
    public static final String ERR_CONTENT_LONG = "ccnr_pm.report.err.content_long";
    public static final String ERR_DETAIL_LONG = "ccnr_pm.report.err.detail_long";
    public static final String ERR_TOO_MANY_TARGETS = "ccnr_pm.report.err.too_many_targets";
    public static final String ERR_TARGET_NAME = "ccnr_pm.report.err.target_name";

    /**
     * 关联玩家名字**格式**不合法（与原版用户名规则一致：3~16 位、字母/数字/下划线）。
     *
     * <p>这条规则**客户端与服务端共用**，因此玩家在面板里敲进去的乱字符会当场被指出来。
     * 而「这个名字在本服存不存在」属于服务端权威（见 {@link com.ccnrcom.pm.player.KnownPlayers}），
     * 不放在这个纯类里——那需要真实玩家名单。
     */
    public static final String ERR_TARGET_NAME_FORMAT = "ccnr_pm.report.err.target_name_format";

    /**
     * 合法玩家名的**字符集**规则：只允许原版用户名允许的字符，长度上限另有常数管。
     *
     * <p>刻意**不**照搬「至少 3 位」：那是正版名的要求，而本模组面向的第三方/离线服务器上
     * 短名字是真实存在的。把真实玩家判成非法，比放过一个乱名字糟得多——
     * 「这个人存不存在」已经由 {@code KnownPlayers} 那道权威判定兜住了。
     */
    private static final java.util.regex.Pattern NAME = java.util.regex.Pattern.compile("^[A-Za-z0-9_]{1,16}$");

    /** 名字是否符合原版用户名规则（纯函数，客户端与服务端共用同一份判定）。 */
    public static boolean isWellFormedName(String name) {
        return name != null && NAME.matcher(name).matches();
    }

    private ReportValidator() {}

    /**
     * 拆分关联玩家的原始输入。
     *
     * <p>分隔符接受半角逗号、全角逗号、分号与任意空白（含换行）——玩家不会去记「应该用哪个分隔符」，
     * 中文输入法下打出全角逗号是最常见的情况，不兼容它会让大量输入被当成一个超长名字而报错。
     * 结果去重（忽略大小写）并保持输入顺序。
     */
    public static List<String> parseTargets(String raw) {
        List<String> out = new ArrayList<>();
        if (raw == null || raw.isBlank()) return List.copyOf(out);
        for (String part : raw.split("[,，;；\\s]+")) {
            String name = part.trim();
            if (name.isEmpty()) continue;
            boolean dup = false;
            for (String seen : out) {
                if (seen.equalsIgnoreCase(name)) {
                    dup = true;
                    break;
                }
            }
            if (!dup) out.add(name);
        }
        return List.copyOf(out);
    }

    /**
     * 校验一份举报草稿。
     *
     * <p>不短路：一次把问题都告诉玩家，避免「改一条报一条」的来回折腾。
     *
     * @param draft 玩家填写内容（已 trim）
     * @param registry 当前类别注册表
     * @param limits 长度边界
     * @return 全部失败项；**空列表表示通过**
     */
    public static List<Error> validate(ReportDraft draft, CategoryRegistry registry, Limits limits) {
        List<Error> errors = new ArrayList<>(3);
        if (draft == null) {
            errors.add(Error.of(ERR_CONTENT_EMPTY));
            return errors;
        }

        // 1) 类别必须是当前注册表里**启用中**的 id
        if (!registry.isSelectable(draft.categoryId())) {
            errors.add(Error.of(ERR_CATEGORY));
        }

        // 2) 举报内容（必填）
        String content = draft.content();
        if (content.isBlank() || content.length() < limits.minContent()) {
            errors.add(Error.of(ERR_CONTENT_EMPTY));
        } else if (content.length() > limits.maxContent()) {
            errors.add(Error.of(ERR_CONTENT_LONG, Integer.toString(limits.maxContent())));
        }

        // 3) 关联玩家（选填）：只在「写了」的时候检查数量与名字长度
        List<String> names = parseTargets(draft.targetRaw());
        if (names.size() > MAX_TARGETS) {
            errors.add(Error.of(ERR_TOO_MANY_TARGETS, Integer.toString(MAX_TARGETS)));
        }
        for (String name : names) {
            if (name.length() > MAX_TARGET_NAME) {
                errors.add(Error.of(ERR_TARGET_NAME, Integer.toString(MAX_TARGET_NAME)));
                break;
            }
            // 格式必须像个人名：乱符号/过短的名字基本不可能对应真实玩家，
            // 与其让它变成一张指向空气的工单，不如当场说清楚（「存不存在」由服务端另判）
            if (!isWellFormedName(name)) {
                errors.add(Error.of(ERR_TARGET_NAME_FORMAT, name));
                break;
            }
        }

        // 4) 详细描述（选填，仅长度上限，无下限）
        if (draft.detail().length() > limits.maxDetail()) {
            errors.add(Error.of(ERR_DETAIL_LONG, Integer.toString(limits.maxDetail())));
        }

        return List.copyOf(errors);
    }
}
