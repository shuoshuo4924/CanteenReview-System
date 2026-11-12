package org.example.canteenreview.common.ratelimit;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存版滑动窗口限流（默认实现，单机可用）。
 *
 * <p>两个必须有的保护：
 * {@link #MAX_KEYS} 限制 key 数量，超出就清理过期窗口，避免被人伪造大量 IP 撑爆内存；
 * {@link #STALE_MILLIS} 让长期不用的 key 能被回收。
 */
@Component
@ConditionalOnProperty(name = "app.rate-limit.store", havingValue = "memory", matchIfMissing = true)
public class InMemoryRateLimitStore implements RateLimitStore {

    /** 不同 key 数量的上限，超过就触发一次过期清理 */
    private static final int MAX_KEYS = 10_000;

    /** 超过这么久没再被访问的 key，直接丢掉 */
    private static final long STALE_MILLIS = Duration.ofHours(2).toMillis();

    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    @Override
    public boolean tryAcquire(String key, int limit, Duration window) {
        long now = System.currentTimeMillis();
        evictIfNeeded(now);

        Window w = windows.computeIfAbsent(key, k -> new Window());
        synchronized (w) {
            prune(w, now - window.toMillis());
            w.lastAccess = now;
            if (w.times.size() >= limit) {
                return false;
            }
            w.times.addLast(now);
            return true;
        }
    }

    @Override
    public boolean isAllowed(String key, int limit, Duration window) {
        Window w = windows.get(key);
        if (w == null) {
            return true;
        }
        synchronized (w) {
            prune(w, System.currentTimeMillis() - window.toMillis());
            return w.times.size() < limit;
        }
    }

    @Override
    public void record(String key) {
        long now = System.currentTimeMillis();
        evictIfNeeded(now);
        Window w = windows.computeIfAbsent(key, k -> new Window());
        synchronized (w) {
            w.times.addLast(now);
            w.lastAccess = now;
        }
    }

    @Override
    public void reset(String key) {
        windows.remove(key);
    }

    @Override
    public long retryAfterSeconds(String key, Duration window) {
        Window w = windows.get(key);
        if (w == null) {
            return 0;
        }
        long now = System.currentTimeMillis();
        synchronized (w) {
            Long first = w.times.peekFirst();
            if (first == null) {
                return 0;
            }
            long wait = first + window.toMillis() - now;
            return wait <= 0 ? 0 : (wait + 999) / 1000;
        }
    }

    /** 丢掉时间窗外的记录（调用方必须持有 w 的锁） */
    private void prune(Window w, long startInclusive) {
        while (!w.times.isEmpty() && w.times.peekFirst() <= startInclusive) {
            w.times.pollFirst();
        }
    }

    /** key 太多时清理一批过期窗口，防止内存无限增长 */
    private void evictIfNeeded(long now) {
        if (windows.size() <= MAX_KEYS) {
            return;
        }
        windows.entrySet().removeIf(entry -> {
            Window w = entry.getValue();
            synchronized (w) {
                return w.times.isEmpty() || now - w.lastAccess > STALE_MILLIS;
            }
        });
    }

    /** 一个 key 的访问记录；Deque 里是每次访问的时间戳（升序） */
    private static final class Window {
        private final Deque<Long> times = new ArrayDeque<>();
        private volatile long lastAccess = System.currentTimeMillis();
    }
}
