/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.pm.ticket;

/**
 * 一种举报类别（数据驱动，定义在 {@code config/ccnr_pm/report_categories.json}）。
 *
 * <p>为什么显示名走语言键而不是直接写进 JSON：内置类别要在中英文客户端上分别显示，
 * 名称放语言包才能跟着语言切换；同时允许管理员的**自定义**类别用 {@code name} 直接写死字面量，
 * 否则新增类别必须同时改语言文件，而语言文件在 jar 里、管理员改不了。
 *
 * @param id 稳定标识，落库存储；一旦有工单引用就不可再改
 * @param customName 自定义显示名（可空）；为空时回退语言键
 * @param order 面板中的排序权重，小者靠前
 * @param enabled 是否在面板中可选；设为 false 只是「不再新增」，历史工单仍可正常显示
 */
public record TicketCategory(String id, String customName, int order, boolean enabled) {

    public TicketCategory {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("类别 id 不可为空");
    }

    /** 内置类别默认显示名所用的语言键。 */
    public String nameKey() {
        return "ccnr_pm.category." + id + ".name";
    }

    /** 内置类别默认描述所用的语言键。 */
    public String descKey() {
        return "ccnr_pm.category." + id + ".desc";
    }

    /** 是否有硬编码显示名（管理员自定义类别）。 */
    public boolean hasCustomName() {
        return customName != null && !customName.isBlank();
    }
}
