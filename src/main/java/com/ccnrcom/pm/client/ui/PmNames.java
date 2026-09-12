/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.pm.client.ui;

import net.minecraft.network.chat.Component;

/**
 * 语言键 → 可读文本的共享入口（类别名、状态名）。
 *
 * <p>为什么要收在一处：面板、HUD 通知卡片、命令输出三处都要把 {@code categoryId} / {@code statusId}
 * 显示成可读文字。各写一份的话，某天只改了其中一处的回退策略，
 * 就会出现「面板里显示正常、卡片上却是一串 ccnr_pm.category.xxx」这种难查的不一致。
 *
 * <p>回退策略：语言包缺键时 Minecraft 会把键**原样返回**，此时显示原始 id。
 * 这比显示一串红色键名有用——自定义类别（管理员在 JSON 里新加的）本来就没有语言键。
 */
public final class PmNames {

    private PmNames() {}

    /** 举报类别的显示名；缺语言键时回退为类别 id。 */
    public static String categoryName(String categoryId) {
        if (categoryId == null || categoryId.isBlank()) return "";
        String key = "ccnr_pm.category." + categoryId + ".name";
        String s = Component.translatable(key).getString();
        return s.equals(key) ? categoryId : s;
    }

    /** 工单状态的显示名。 */
    public static String statusName(String statusId) {
        if (statusId == null || statusId.isBlank()) return "";
        return Component.translatable("ccnr_pm.status." + statusId).getString();
    }
}
