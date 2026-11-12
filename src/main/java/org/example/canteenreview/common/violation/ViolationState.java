package org.example.canteenreview.common.violation;

/**
 * 一个 key 的违规状态快照。
 *
 * <p>做成不可变对象：读取时拿到的是副本，改完必须整体写回，
 * 这样内存实现和 Redis 实现的语义完全一致，不会出现"改了一半"的中间态。
 *
 * @param count        计数窗口内的违规次数
 * @param windowStart  计数窗口起点（毫秒时间戳）
 * @param blockedUntil 封禁到期时间（毫秒时间戳），0 表示未封禁
 * @param lastCategory 最近一次命中的拦截类别（"词库""手机号"等），后台展示用
 */
public record ViolationState(int count, long windowStart, long blockedUntil, String lastCategory) {
}
