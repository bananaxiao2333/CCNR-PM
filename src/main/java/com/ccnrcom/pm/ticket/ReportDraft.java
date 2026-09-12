/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.pm.ticket;

/**
 * 举报面板中玩家填写的原始内容（客户端 → 服务端的意图）。
 *
 * <p>这是「意图」而不是「工单」：它没有 id/时间/状态，也**不被信任**。
 * 服务端收到后会逐项重新校验（{@link ReportValidator}），再把校验通过的内容交给仓储落库。
 * 之所以要有一个独立的中间结构，是为了让「玩家说了什么」与「服务端认定成立的是什么」在类型上就分开，
 * 避免某天有人图省事把这个对象直接当工单存进去。
 *
 * <p>{@code targetRaw} 是**未经拆分的原始输入**（例如 {@code "Bob, Carol 阿明"}）。
 * 拆分规则属于校验的一部分，服务端拆分即可；客户端也调同一份实现做预览。
 * 若这里存的是已拆好的列表，两边就会各写一套拆分逻辑，最终在「中文逗号算不算分隔符」上产生分歧。
 *
 * @param targetRaw 关联玩家的原始输入（**选填**，可为空）
 * @param categoryId 举报类别 id
 * @param content 举报内容（由 {@code /a} 那条消息预填，可编辑；必填）
 * @param detail 举报详细描述（**选填**，可含换行）
 */
public record ReportDraft(String targetRaw, String categoryId, String content, String detail) {

    public ReportDraft {
        targetRaw = targetRaw == null ? "" : targetRaw.trim();
        categoryId = categoryId == null ? "" : categoryId.trim();
        content = content == null ? "" : content.strip();
        detail = detail == null ? "" : detail.strip();
    }
}
