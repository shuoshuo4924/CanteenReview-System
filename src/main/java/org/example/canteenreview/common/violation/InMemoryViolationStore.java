package org.example.canteenreview.common.violation;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存版违规状态存储（默认实现，单机够用，重启清零）。
 *
 * <p>key 由用户 id 和客户端 IP 组成，IP 可以被伪造，所以必须有数量上限，
 * 否则一个人换着 IP 刷就能把内存撑爆。
 */
@Component
@ConditionalOnProperty(name = "app.rate-limit.store", havingValue = "memory", matchIfMissing = true)
public class InMemoryViolationStore implements ViolationStore {

    /** 不同 key 的数量上限，和限流存储保持一致 */
    private static final int MAX_KEYS = 10_000;

    /** 超过这么久没用的记录可以回收：覆盖最长的 7 天计数窗口 + 12 小时封禁 */
    private static final long STALE_MILLIS = Duration.ofDays(8).toMillis();

    private final Map<String, ViolationState> states = new ConcurrentHashMap<>();

    @Override
    public ViolationState get(String key) {
        return key == null ? null : states.get(key);
    }

    @Override
    public void put(String key, ViolationState state) {
        if (key == null || state == null) {
            return;
        }
        if (states.size() >= MAX_KEYS && !states.containsKey(key)) {
            evict();
        }
        states.put(key, state);
    }

    @Override
    public boolean remove(String key) {
        return key != null && states.remove(key) != null;
    }

    @Override
    public Map<String, ViolationState> snapshot() {
        return Map.copyOf(states);
    }

    /** 先清过期，还满就丢掉一批（宁可丢处罚记录，也不能把内存撑爆） */
    private void evict() {
        long now = System.currentTimeMillis();
        states.entrySet().removeIf(entry -> {
            ViolationState state = entry.getValue();
            return state.blockedUntil() <= now && now - state.windowStart() > STALE_MILLIS;
        });
        if (states.size() < MAX_KEYS) {
            return;
        }
        Iterator<String> it = states.keySet().iterator();
        int removed = 0;
        while (it.hasNext() && removed < MAX_KEYS / 10) {
            it.next();
            it.remove();
            removed++;
        }
    }
}
