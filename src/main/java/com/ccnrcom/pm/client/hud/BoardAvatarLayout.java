/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.pm.client.hud;

import com.ccnrcom.pm.ticket.TicketBoard;
import java.util.ArrayList;
import java.util.List;

/**
 * 看板卡片上「头像 → 玩家」的**位置索引**（纯逻辑，无 MC 依赖，可直接单测）。
 *
 * <h2>为什么要有这个类</h2>
 * 头像行原来只有「画」一处（{@link TicketNoticeOverlay#renderAvatarRow}），位置算法直接写在绘制代码里。
 * 现在交互看板（{@code PmBoardScreen}）要能**点中**头像，于是同一套位置就有了第二个使用者。
 * 两处各算一份坐标必然漂移——症状是「点左边的头像弹出右边玩家的菜单」，
 * 而且只在某些名字长度/关联人数下才出现，极难复现。因此位置算法收成这一个纯类，
 * 绘制与命中判定都从这里取坐标，几何只有一份。
 *
 * <h2>坐标约定</h2>
 * 入参是卡片的屏幕坐标（左上角），返回的是**屏幕坐标**（绝对），调用方不需要再做加法。
 */
public final class BoardAvatarLayout {

    /**
     * 一个头像槽位。
     *
     * @param name      玩家名（卡片上的小名字，也是右键菜单的标题）
     * @param uuid      玩家 UUID；为空串表示这条关联玩家是**手输的离线名字**（没有 UUID）
     * @param x         头像左边缘（屏幕坐标）
     * @param y         头像上边缘（屏幕坐标）
     * @param submitter 是否提交者（提交者用亮框，且固定排在第一位）
     */
    public record Slot(String name, String uuid, int x, int y, boolean submitter) {
        public Slot {
            name = name == null ? "" : name;
            uuid = uuid == null ? "" : uuid;
        }

        /** 头像边长（与绘制共用同一常量，别在这里再写一个数）。 */
        public int size() {
            return NoticeCardLayout.HEAD;
        }

        public int x2() {
            return x + size();
        }

        public int y2() {
            return y + size();
        }

        public boolean hit(double mx, double my) {
            return mx >= x && mx < x2() && my >= y && my < y2();
        }

        /** 是否具备「按 UUID 精确定位玩家」的条件（离线手输名字没有 UUID）。 */
        public boolean hasUuid() {
            return !uuid.isBlank();
        }

        /** UUID 解析结果；名字型条目（无 UUID）或格式非法时为 null。 */
        public java.util.UUID uuidOrNull() {
            if (uuid.isBlank()) return null;
            try {
                return java.util.UUID.fromString(uuid);
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
    }

    /** 提交者头像相对卡片左缘的 X（与 {@code renderAvatarRow} 一致：紧贴内边距）。 */
    public static int reporterHeadX() {
        return NoticeCardLayout.PAD;
    }

    /** 第 {@code index} 个关联玩家头像相对卡片左缘的 X。 */
    public static int targetHeadX(int index) {
        return NoticeCardLayout.PAD
                + NoticeCardLayout.HEAD
                + NoticeCardLayout.DIVIDER_GAP
                + 1
                + NoticeCardLayout.DIVIDER_GAP
                + Math.max(0, index) * NoticeCardLayout.HEAD;
    }

    /**
     * 卡片的全部头像槽位（提交者在前，之后是**画得出来**的关联玩家）。
     *
     * <p>只列出真正画出来的那些：溢出时少画的那个（{@link NoticeCardLayout#relatedToDraw}）
     * 不该有可点区域——看不见却能点，等于给了一个「不知道会点到谁」的按钮。
     */
    public static List<Slot> slots(TicketBoard.Card card, int cardX, int cardY) {
        List<Slot> out = new ArrayList<>();
        if (card == null) return out;

        int headY = cardY + NoticeCardLayout.avatarRowY();
        if (!card.reporter().isBlank()) {
            out.add(new Slot(card.reporter(), card.reporterUuid(), cardX + reporterHeadX(), headY, true));
        }
        int toDraw = NoticeCardLayout.relatedToDraw(card.targets().size());
        for (int i = 0; i < toDraw; i++) {
            TicketBoard.TargetRef t = card.targets().get(i);
            out.add(new Slot(t.name(), t.uuid(), cardX + targetHeadX(i), headY, false));
        }
        return out;
    }

    /** 命中测试：返回被点中的槽位，没点中返回 null。 */
    public static Slot hit(List<Slot> slots, double mx, double my) {
        for (Slot s : slots) {
            if (s.hit(mx, my)) return s;
        }
        return null;
    }

    private BoardAvatarLayout() {}
}
