package org.example.canteenreview.common.violation;

import java.util.Map;

/**
 * 评论违规状态的存储后端。
 *
 * <p>和 {@link org.example.canteenreview.common.ratelimit.RateLimitStore} 拆开的理由一样：
 * 单机跑内存，多实例部署换成 Redis，业务代码（ReviewViolationGuard）一行都不用改。
 *
 * <p>语义约定：{@link #get} 返回不可变快照，{@link #put} 整体覆盖。
 * 需要"读-改-写"的地方由调用方自己加锁（单实例内足够；
 * 多实例之间只有极小概率同时处理同一用户的两次违规，不做分布式锁）。
 */
public interface ViolationStore {

    /** 查一个 key 的状态，不存在返回 null */
    ViolationState get(String key);

    /** 整体写入（覆盖）一个 key 的状态 */
    void put(String key, ViolationState state);

    /** 删除一个 key，返回是否真的删掉了 */
    boolean remove(String key);

    /** 全量快照：后台展示封禁列表、定期清理过期记录时用 */
    Map<String, ViolationState> snapshot();
}
