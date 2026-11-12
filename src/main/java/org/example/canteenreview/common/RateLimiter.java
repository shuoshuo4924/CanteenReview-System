package org.example.canteenreview.common;

import jakarta.servlet.http.HttpServletRequest;
import org.example.canteenreview.common.ratelimit.RateLimitStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 限流门面：业务代码只认这里的方法，具体存在哪里由 {@link RateLimitStore} 决定。
 *
 * <p>默认是内存实现（单机够用，重启清零）；
 * 多实例部署时把 {@code app.rate-limit.store} 配成 {@code redis}，
 * 状态就变成共享的，Controller 里的调用一行都不用改。
 */
@Component
public class RateLimiter {

    /**
     * 是否信任反向代理传来的 X-Forwarded-For。
     * 默认 false：直连场景下这个头是客户端自己填的，信任它等于把限流开关交给攻击者。
     * 只有在 Nginx 等代理已经用 proxy_set_header 覆盖该头时才打开。
     */
    @Value("${app.trust-proxy:false}")
    private boolean trustProxy;

    private final RateLimitStore store;

    public RateLimiter(RateLimitStore store) {
        this.store = store;
    }

    /**
     * 记一次访问：时间窗内已经到达 limit 次则拒绝，并且不做记录。
     *
     * @param key    业务维度，如 "login:ip:127.0.0.1"
     * @param limit  时间窗内允许的最大次数
     * @param window 时间窗长度
     * @return true 表示放行
     */
    public boolean tryAcquire(String key, int limit, Duration window) {
        return store.tryAcquire(key, limit, window);
    }

    /**
     * 只查询、不记录。
     * 用于多条规则组合的业务（比如"60 秒 1 条"且"每天 20 条"）：
     * 先全部查一遍，业务成功后再统一 {@link #record(String)}，避免失败也占额度。
     */
    public boolean isAllowed(String key, int limit, Duration window) {
        return store.isAllowed(key, limit, window);
    }

    /** 记一次访问（配合 {@link #isAllowed} 使用） */
    public void record(String key) {
        store.record(key);
    }

    /**
     * 清空某个 key 的全部记录。
     * 典型场景：连续答错若干次后锁定，一旦校验成功就把失败计数归零，
     * 避免用户明明已经答对、却被前面几次失败连带锁住。
     */
    public void reset(String key) {
        store.reset(key);
    }

    /**
     * 被限流时还要等多少秒才能再试，用于给出更具体的提示。
     * 没被限流返回 0。
     */
    public long retryAfterSeconds(String key, Duration window) {
        return store.retryAfterSeconds(key, window);
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
}
