package org.example.canteenreview.common.ratelimit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Redis 版滑动窗口限流：多实例部署时状态共享，重启不清零。
 *
 * <p>用 ZSET 存"每次访问的时间戳"，score 就是毫秒时间戳，
 * 清理过期记录 = ZREMRANGEBYSCORE，计数 = ZCARD。
 *
 * <p>tryAcquire 必须原子：先查后写中间被别的请求插进来就会超发，
 * 所以整段逻辑交给 Lua 脚本，由 Redis 单线程执行。
 *
 * <p>启用方式：{@code app.rate-limit.store=redis}，并配置 spring.data.redis。
 * 没配 Redis 但误开了这个开关，所有请求都会抛异常 —— 默认是 memory，不会误触。
 */
@Component
@ConditionalOnProperty(name = "app.rate-limit.store", havingValue = "redis")
public class RedisRateLimitStore implements RateLimitStore {

    private static final Logger log = LoggerFactory.getLogger(RedisRateLimitStore.class);

    /** 统一前缀，避免和同一个 Redis 里别的业务撞 key */
    private static final String PREFIX = "canteen:rl:";

    /** 记录的最长保留时间：窗口再长也不会超过它 */
    private static final Duration MAX_TTL = Duration.ofHours(2);

    /**
     * KEYS[1] = 业务 key
     * ARGV[1] = 当前毫秒时间戳，ARGV[2] = 窗口毫秒数，ARGV[3] = 上限次数，ARGV[4] = 本次访问的唯一成员
     */
    private static final DefaultRedisScript<Long> TRY_ACQUIRE_SCRIPT = new DefaultRedisScript<>(
            "redis.call('ZREMRANGEBYSCORE', KEYS[1], 0, tonumber(ARGV[1]) - tonumber(ARGV[2])) "
                    + "if redis.call('ZCARD', KEYS[1]) >= tonumber(ARGV[3]) then return 0 end "
                    + "redis.call('ZADD', KEYS[1], ARGV[1], ARGV[4]) "
                    + "redis.call('PEXPIRE', KEYS[1], math.max(tonumber(ARGV[2]), 60000)) "
                    + "return 1",
            Long.class);

    private final StringRedisTemplate redis;

    public RedisRateLimitStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public boolean tryAcquire(String key, int limit, Duration window) {
        long now = System.currentTimeMillis();
        String redisKey = PREFIX + key;
        try {
            Long result = redis.execute(TRY_ACQUIRE_SCRIPT, List.of(redisKey),
                    String.valueOf(now),
                    String.valueOf(window.toMillis()),
                    String.valueOf(limit),
                    now + ":" + UUID.randomUUID());
            return result != null && result > 0;
        } catch (Exception e) {
            // Redis 挂了不应该让登录、评价这些主流程直接崩掉：放行并留下明显日志，
            // 由运维从日志里发现问题。宁可短时限流失效，也不要整站不可用。
            log.error("Redis 限流不可用，本次请求放行：key={}", key, e);
            return true;
        }
    }

    @Override
    public boolean isAllowed(String key, int limit, Duration window) {
        String redisKey = PREFIX + key;
        try {
            prune(redisKey, window);
            Long size = redis.opsForZSet().zCard(redisKey);
            return size == null || size < limit;
        } catch (Exception e) {
            log.error("Redis 限流不可用，本次查询放行：key={}", key, e);
            return true;
        }
    }

    @Override
    public void record(String key) {
        String redisKey = PREFIX + key;
        try {
            long now = System.currentTimeMillis();
            redis.opsForZSet().add(redisKey, now + ":" + UUID.randomUUID(), now);
            redis.expire(redisKey, MAX_TTL);
        } catch (Exception e) {
            log.error("Redis 限流记录失败：key={}", key, e);
        }
    }

    @Override
    public void reset(String key) {
        try {
            redis.delete(PREFIX + key);
        } catch (Exception e) {
            log.error("Redis 限流重置失败：key={}", key, e);
        }
    }

    @Override
    public long retryAfterSeconds(String key, Duration window) {
        String redisKey = PREFIX + key;
        try {
            prune(redisKey, window);
            Set<ZSetOperations.TypedTuple<String>> first =
                    redis.opsForZSet().rangeWithScores(redisKey, 0, 0);
            if (first == null || first.isEmpty()) {
                return 0;
            }
            Double score = first.iterator().next().getScore();
            if (score == null) {
                return 0;
            }
            long wait = score.longValue() + window.toMillis() - System.currentTimeMillis();
            return wait <= 0 ? 0 : (wait + 999) / 1000;
        } catch (Exception e) {
            log.error("Redis 限流查询等待时间失败：key={}", key, e);
            return 0;
        }
    }

    private void prune(String redisKey, Duration window) {
        redis.opsForZSet().removeRangeByScore(redisKey, 0, System.currentTimeMillis() - window.toMillis());
    }
}
