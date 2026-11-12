package org.example.canteenreview.common;

import java.time.LocalDate;

/**
 * AI 每日额度的共享常量。
 *
 * 对话、评价总结、健康建议、建议箱助手都算同一次调用，共用这一个总闸，
 * 避免某个入口被刷时其他入口还能继续烧钱。
 *
 * key 里带日期，额度就是「自然日」重置：凌晨用完不会卡到第二天同一时刻。
 */
public final class AiQuota {

    /** 全站每天最多调用多少次大模型 */
    public static final int DAILY_LIMIT = 2000;

    private static final String KEY_PREFIX = "ai:global:daily:";

    private AiQuota() {
    }

    /** 当天额度的限流 key，例如 ai:global:daily:2026-11-12 */
    public static String dailyKey() {
        return KEY_PREFIX + LocalDate.now();
    }
}
