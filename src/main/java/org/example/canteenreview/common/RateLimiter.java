package org.example.canteenreview.common;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存版滑动窗口限流，用于挡掉重复提交 / 暴力尝试 / 刷 AI 接口。
 *
 * 只适合单机：多实例部署时每个 JVM 各算各的，需要换成 Redis 之类的共享存储。
 * 内存里的 key 有上限（{@link #MAX_KEYS}）和过期清理，避免被大量伪造 IP 撑爆。
 */
@Component
public class RateLimiter {

    /** 不同 key 数量的上限，超过就触发一次过期清理 */
    private static final int MAX_KEYS = 10_000;

    /** 超过这么久没再被访问的 key，直接丢掉 */
    private static final long STALE_MILLIS = Duration.ofHours(2).toMillis();

    /**
     * 是否信任反向代理传来的 X-Forwarded-For。
     * 默认 false：直连场景下这个头是客户端自己填的，信任它等于把限流开关交给攻击者。
     * 只有在 Nginx 等代理已经用 proxy_set_header 覆盖该头时才打开。
     */
    @Value("${app.trust-proxy:false}")
    private boolean trustProxy;

    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    /**
     * 记一次访问：时间窗内已经到达 limit 次则拒绝，并且不做记录。
     *
     * @param key    业务维度，如 "login:ip:127.0.0.1"
     * @param limit  时间窗内允许的最大次数
     * @param window 时间窗长度
     * @return true 表示放行
     */
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

    /**
     * 只查询、不记录。
     * 用于多条规则组合的业务（比如"60 秒 1 条"且"每天 20 条"）：
     * 先全部查一遍，业务成功后再统一 {@link #record(String)}，避免失败也占额度。
     */
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

    /** 记一次访问（配合 {@link #isAllowed} 使用） */
    public void record(String key) {
        long now = System.currentTimeMillis();
        evictIfNeeded(now);
        Window w = windows.computeIfAbsent(key, k -> new Window());
        synchronized (w) {
            w.times.addLast(now);
            w.lastAccess = now;
        }
    }

    /**
     * 被限流时还要等多少秒才能再试，用于给出更具体的提示。
     * 没被限流返回 0。
     */
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

    /**
     * 取客户端 IP，仅用于拼限流 key。
     * 默认只认 TCP 连接的真实地址：X-Forwarded-For 是客户端可以随便伪造的头，
     * 无条件信任它，攻击者每次换一个头就能绕过所有限流。
     * 部署在可信代理后面时，把 app.trust-proxy 设为 true，
     * 并让代理覆盖该头（proxy_set_header X-Forwarded-For $remote_addr）。
     */
    public String clientIp(HttpServletRequest request) {
        if (trustProxy) {
            String forwarded = request.getHeader("X-Forwarded-For");
            if (forwarded != null && !forwarded.isBlank()) {
                int comma = forwarded.indexOf(',');
                String first = comma > 0 ? forwarded.substring(0, comma) : forwarded;
                if (!first.isBlank()) {
                    return first.trim();
                }
            }
            String realIp = request.getHeader("X-Real-IP");
            if (realIp != null && !realIp.isBlank()) {
                return realIp.trim();
            }
        }
        return request.getRemoteAddr();
    }

    /** 一个 key 的访问记录；Deque 里是每次访问的时间戳（升序） */
    private static final class Window {
        private final Deque<Long> times = new ArrayDeque<>();
        private volatile long lastAccess = System.currentTimeMillis();
    }
}
