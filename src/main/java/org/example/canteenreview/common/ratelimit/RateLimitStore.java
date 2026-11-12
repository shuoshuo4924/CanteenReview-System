package org.example.canteenreview.common.ratelimit;

import java.time.Duration;

/**
 * 限流状态的存储后端。
 *
 * <p>把"怎么存"和"怎么用"拆开的唯一目的：单机跑内存，多实例部署换成 Redis，
 * 业务代码（Controller 里的 tryAcquire 调用）一行都不用改。
 *
 * <p>语义约定：{@link #isAllowed} 只查不记，{@link #record} 才占额度，
 * 这样多规则组合的业务可以先全部检查、成功后再统一记账。
 */
public interface RateLimitStore {

    /** 记一次访问：窗口内已满则拒绝，并且不占用额度 */
    boolean tryAcquire(String key, int limit, Duration window);

    /** 只查询、不记录 */
    boolean isAllowed(String key, int limit, Duration window);

    /** 记一次访问（配合 isAllowed 使用） */
    void record(String key);

    /** 清空某个 key 的记录 */
    void reset(String key);

    /** 被限流时还要等多少秒；未被限流返回 0 */
    long retryAfterSeconds(String key, Duration window);
}
