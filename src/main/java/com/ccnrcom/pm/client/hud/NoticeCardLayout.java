/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.pm.client.hud;

/**
 * 看板卡片与整块看板的**几何**（纯逻辑，无 MC 依赖，可直接单测）。
 *
 * <p>这个文件是**三次返工**的结果，每条约束都对应一次真实反馈：
 * 尺寸太大 → 锁尺寸上限；4:3 比例错 → 锁 16:9；垂直居中「分布奇怪不靠边」→ 锁两端对齐；
 * 内容改为「标题 + 类别 + 详细描述 + 头像带名字」→ 重新排版并锁住各行不重叠。
 *
 * <h2>版式（用户指定）</h2>
 * <pre>
 * ┌─────────────────────────┐  160 × 90（16:9）
 * │ 他隔着墙把我矿挖了  作弊/外挂│  ← 左：标题（举报内容）；右：类别
 * │ 我在矿洞里被他隔墙挖了…     │  ← 详细描述（1~2 行）
 * │   Alice   Bob            │  ← 每个头像上方一个很小的小字（玩家名）
 * │   ▣   │   ▢▢             │  ← 头像贴底；竖线分隔「提交者 | 关联玩家」
 * └─────────────────────────┘
 * </pre>
 *
 * <p>**卡片上不放工单编号**：编号对处理没有意义，管理员扫看板要的是「什么事、谁报的」。
 * 编号只在管理面板里出现。
 *
 * <h2>对齐：靠边，不居中</h2>
 * 内容顶到内边距：标题行在顶、头像行在底，两端各自靠边；外边距很小以贴近屏幕角。
 *
 * <h2>坐标约定</h2>
 * 所有 Y 都是**相对卡片顶边**的偏移，由调用方加上 {@code cardY} 得到屏幕坐标。
 */
public final class NoticeCardLayout {

    /** 卡片宽度。比上一版略宽，因为标题与类别要在同一行分列左右。 */
    public static final int CARD_W = 160;

    /** 卡片高度：16:9 横版，由宽度推出，**不要手填两个数**。 */
    public static final int CARD_H = CARD_W * 9 / 16;

    /** 卡片距屏幕左上角的间距。要「靠边」，所以很小；留 2px 是为了不贴死屏幕边缘。 */
    public static final int MARGIN = 2;

    /** 卡片之间的竖向间距。 */
    public static final int STACK_GAP = 2;

    /** 同屏最多画几张卡片，其余折叠成「还有 N 张」。 */
    public static final int MAX_VISIBLE = 4;

    /** 卡片内边距。 */
    public static final int PAD = 5;

    /** 文字行距。 */
    public static final int LINE = 11;

    /** 小名字行的高度（比正文行矮一档，视觉上「小小的」）。 */
    public static final int NAME_H = 9;

    /** 头像边长；行内**不设间隔**，逐个紧挨着排。 */
    public static final int HEAD = 22;

    /** 竖线左右各留的间距（整行唯一被允许的缝，用于分隔提交者与关联玩家）。 */
    public static final int DIVIDER_GAP = 2;

    /**
     * 状态标签最大宽度。
     *
     * <p>中文状态名固定 3 字（待处理/处理中/已办结/已驳回）≈ 27px；英文最长 "Rejected" ≈ 44px，
     * 所以上限按英文取。超长（管理员自定义语言）就裁剪，绝不挤掉标题。
     */
    public static final int STATUS_MAX_W = 44;

    /**
     * 标题（举报内容）的最小可用宽度。
     *
     * <p>用户反馈「状态不好分辨」后把状态标签放上标题行，横向空间就更紧了。
     * 定一条下限：宁可把**类别**裁到没有，也不能让标题缩成看不懂的几个字
     * （卡片回答的是「什么事」，类别在列表与详情里还有一份）。
     */
    public static final int MIN_TITLE_W = 46;

    /** 标题与右侧标签组之间的间距。 */
    public static final int TAG_GAP = 5;

    /** 类别与状态标签之间的间距。 */
    public static final int STATUS_GAP = 4;

    /** 详细描述最多显示几行。 */
    public static final int DETAIL_LINES = 2;

    /** 交互模式下卡片底部的按钮高度。 */
    public static final int BUTTON_H = 14;

    /** 内容宽度（去掉左右内边距）。 */
    public static int innerWidth() {
        return CARD_W - PAD * 2;
    }

    /**
     * 状态标签可用宽度：按实际文本测量后夹到 {@code [20, STATUS_MAX_W]}。
     *
     * <p>下限 20 是为了让「已驳回」这类短标签不至于被压成两字；宽度由**测量**决定，
     * 因此换语言（中↔英）不会把布局挤坏。
     */
    public static int statusWidth(int measured) {
        return Math.max(20, Math.min(measured, STATUS_MAX_W));
    }

    /**
     * 类别可用宽度：右侧先让给状态标签，再给标题留出 {@link #MIN_TITLE_W}。
     *
     * <p>**类别的优先级最低**：状态是用户要一眼分辨的东西、标题是卡片的主内容，
     * 类别在工单列表与详情里都还有一份。三者相加恰好等于 {@link #innerWidth()}（由本方法与
     * {@link #titleWidthFor} 共同保证），因此永远不会互相重叠。
     */
    public static int categoryWidthFor(int statusW, int measuredCategory) {
        int room = innerWidth() - statusW - STATUS_GAP - TAG_GAP - MIN_TITLE_W;
        return Math.max(0, Math.min(measuredCategory, room));
    }

    /** 标题可用宽度：扣掉右侧类别与状态标签后的剩余（由构造保证 ≥ {@link #MIN_TITLE_W}）。 */
    public static int titleWidthFor(int statusW, int categoryWidth) {
        return innerWidth() - statusW - STATUS_GAP - categoryWidth - TAG_GAP;
    }

    /** 状态标签左边缘相对卡片左缘的 X（右对齐到内边距）。 */
    public static int statusX(int statusW) {
        return CARD_W - PAD - statusW;
    }

    /** 类别右边缘相对卡片左缘的 X（贴在状态标签左侧）。 */
    public static int categoryRight(int statusW) {
        return CARD_W - PAD - statusW - STATUS_GAP;
    }

    /** 标题行顶端（同时是内容块顶端）。 */
    public static int titleY() {
        return PAD;
    }

    /** 详细描述顶端。 */
    public static int detailY() {
        return titleY() + LINE;
    }

    /** 头像行顶端：**贴底**，让文字在顶、头像在底，两端各自靠边。 */
    public static int avatarRowY() {
        return CARD_H - PAD - HEAD;
    }

    public static int avatarRowBottom() {
        return avatarRowY() + HEAD;
    }

    /** 小名字行顶端（在头像正上方）。 */
    public static int nameY() {
        return avatarRowY() - NAME_H;
    }

    /** 详细描述可用高度（到小名字行为止）。 */
    public static int detailHeight() {
        return nameY() - detailY() - 2;
    }

    /** 关联玩家一侧可用的像素宽度（已扣掉提交者、竖线与两侧间距）。 */
    public static int relatedWidth() {
        return innerWidth() - HEAD - DIVIDER_GAP - 1 - DIVIDER_GAP;
    }

    /** 关联玩家一侧最多能画几个头像。 */
    public static int relatedCapacity() {
        return Math.max(0, relatedWidth() / HEAD);
    }

    /** 关联玩家数量是否超出能画下的数量。 */
    public static boolean overflows(int total) {
        return total > relatedCapacity();
    }

    /**
     * 实际画几个关联头像。
     *
     * <p>溢出时**少画一个**，把腾出来的位置留给 {@code +N} 计数：
     * 否则管理员会以为关联玩家只有画出来的这几个，而漏掉真实存在的后面几个人。
     */
    public static int relatedToDraw(int total) {
        int capacity = relatedCapacity();
        if (total <= capacity) return total;
        return Math.max(0, capacity - 1);
    }

    /** 第 {@code index} 张卡片（只读版式）的屏幕 Y 坐标。 */
    public static int cardY(int index) {
        return MARGIN + index * (CARD_H + STACK_GAP);
    }

    /**
     * 交互模式下卡片的整体高度：在只读版式下方追加一排按钮。
     *
     * <p>按住 P 进入交互模式后，每张卡片底部多出「认领 / 忽略」两个按钮——
     * 这是 HUD 浮层做不到的事（浮层无光标坐标），所以交互模式由 {@code PmBoardScreen} 承担。
     */
    public static int cardHeightInteractive() {
        return CARD_H + BUTTON_H + 2;
    }

    /** 交互模式下第 {@code index} 张卡片的屏幕 Y 坐标。 */
    public static int cardYInteractive(int index) {
        return MARGIN + index * (cardHeightInteractive() + STACK_GAP);
    }

    private NoticeCardLayout() {}
}
