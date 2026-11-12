package org.example.canteenreview.common.violation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * Redis 版违规状态存储：多实例部署时共享，重启不清零。
 *
 * <p>值存成一个三元组字符串 {@code count|windowStart|blockedUntil}，
 * 比 JSON 省事，也不依赖序列化配置。
 *
 * <p>启用方式：{@code app.rate-limit.store=redis}，并配置 spring.data.redis。
 * Redis 不可用时和限流一样"降级放行"：读不到就当没有违规史，
 * 写失败只记日志。理由同样是——缓存故障不该把评论功能一起拖下水。
 *
 * <p>后台列表走 SCAN 而不是 KEYS：KEYS 会阻塞 Redis 主线程，
 * 线上数据量一大就是事故。
 */
@Component
@ConditionalOnProperty(name = "app.rate-limit.store", havingValue = "redis")
public class RedisViolationStore implements ViolationStore {

    private static final Logger log = LoggerFactory.getLogger(RedisViolationStore.class);

    /** 统一前缀，避免和同一个 Redis 里别的业务撞 key */
    private static final String PREFIX = "canteen:violation:";

    /** 记录保留时间：覆盖最长 7 天计数窗口 + 12 小时封禁，留一天余量 */
    private static final Duration TTL = Duration.ofDays(8);

    /** 后台列表一次最多扫这么多条，避免极端情况把页面拖死 */
    private static final int MAX_SNAPSHOT = 5_000;

    private static final String SEPARATOR = "|";

    private final StringRedisTemplate redis;

    public RedisViolationStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public ViolationState get(String key) {
        if (key == null) {
            return null;
        }
        try {
            return parse(redis.opsForValue().get(PREFIX + key));
        } catch (Exception e) {
            log.error("读取违规状态失败，按无违规处理：key={}", key, e);
            return null;
        }
    }

    @Override
    public void put(String key, ViolationState state) {
        if (key == null || state == null) {
            return;
        }
        try {
            redis.opsForValue().set(PREFIX + key, serialize(state), TTL);
        } catch (Exception e) {
            log.error("写入违规状态失败，本次处罚未生效：key={}", key, e);
        }
    }

    @Override
    public boolean remove(String key) {
        if (key == null) {
            return false;
        }
        try {
            return Boolean.TRUE.equals(redis.delete(PREFIX + key));
        } catch (Exception e) {
            log.error("删除违规状态失败：key={}", key, e);
            return false;
        }
    }

    @Override
    public Map<String, ViolationState> snapshot() {
        Map<String, ViolationState> result = new HashMap<>();
        ScanOptions options = ScanOptions.scanOptions().match(PREFIX + "*").count(500).build();
        try (Cursor<String> cursor = redis.scan(options)) {
            while (cursor.hasNext() && result.size() < MAX_SNAPSHOT) {
                String redisKey = cursor.next();
                ViolationState state = parse(redis.opsForValue().get(redisKey));
                if (state != null) {
                    result.put(redisKey.substring(PREFIX.length()), state);
                }
            }
        } catch (Exception e) {
            log.error("扫描违规状态失败，后台列表可能不完整", e);
        }
        return result;
    }

    private static String serialize(ViolationState state) {
        return state.count() + SEPARATOR + state.windowStart() + SEPARATOR + state.blockedUntil()
                + SEPARATOR + (state.lastCategory() == null ? "" : state.lastCategory());
    }

    /**
     * 解析失败按"没有记录"处理。
     * 兼容旧的三段格式（没有类别字段），滚动升级时不会把在用的封禁丢掉。
     */
    private ViolationState parse(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String[] parts = value.split("\\" + SEPARATOR, -1);
        if (parts.length < 3) {
            return null;
        }
        try {
            String category = parts.length > 3 ? parts[3] : "";
            return new ViolationState(Integer.parseInt(parts[0]), Long.parseLong(parts[1]),
                    Long.parseLong(parts[2]), category);
        } catch (NumberFormatException e) {
            log.warn("违规状态格式异常，已忽略：{}", value);
            return null;
        }
    }
}
