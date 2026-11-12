package org.example.canteenreview.common;

import org.example.canteenreview.common.violation.ViolationState;
import org.example.canteenreview.common.violation.ViolationStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * 评论违规的阶梯处罚。
 *
 * <p>用户维度：前 3 次不合规只计数、照常放行（给正常用户改错的机会），
 * 第 4 次起禁止再提交，时长为 {@code 10 分钟 × 2^(违规次数 - 4)}：
 * 第 4 次 10 分钟、第 5 次 20 分钟、第 6 次 40 分钟……封顶 12 小时。
 * 封顶是必要的：纯指数增长到几十次就是几百天，等于永久封号，误判时没法恢复。
 *
 * <p>IP 维度：只做兜底，防止换个新账号继续刷。门槛更高（10 次）、时长固定（10 分钟）、
 * 不翻倍 —— 校园网宿舍和机房的出口 IP 是多人共用的，用同一套指数规则会让一个人的行为连坐全楼。
 *
 * <p>计数窗口：用户 7 天、IP 24 小时。超过窗口没有新违规就重新计数，
 * 避免一次手滑在半年后还在翻倍。
 *
 * <p>封禁期内再次记账不会累加次数、也不会延长封禁，
 * 这样"用户在被禁时多点几次提交"不会把自己越锁越久。
 *
 * <p>状态存在 {@link ViolationStore} 里：默认内存（单机，重启清零），
 * 配 {@code app.rate-limit.store=redis} 后变成多实例共享、重启不丢。
 * 后台可以在评论审核页看到当前被封的对象，并手动解除。
 */
@Component
public class ReviewViolationGuard {

    private static final Logger log = LoggerFactory.getLogger(ReviewViolationGuard.class);

    private static final String USER_PREFIX = "user:";
    private static final String IP_PREFIX = "ip:";

    private static final Policy USER_POLICY = new Policy(
            3, Duration.ofMinutes(10).toSeconds(), Duration.ofHours(12).toSeconds(),
            Duration.ofDays(7).toMillis(), true);

    private static final Policy IP_POLICY = new Policy(
            10, Duration.ofMinutes(10).toSeconds(), Duration.ofMinutes(10).toSeconds(),
            Duration.ofHours(24).toMillis(), false);

    private final ViolationStore store;

    /** 取当前时间；测试里换成可推进的假时钟，逻辑一行不改 */
    private final LongSupplier clock;

    @Autowired
    public ReviewViolationGuard(ViolationStore store) {
        this(store, System::currentTimeMillis);
    }

    /** 仅供测试注入假时钟 */
    ReviewViolationGuard(ViolationStore store, LongSupplier clock) {
        this.store = store;
        this.clock = clock;
    }

    /**
     * 一个维度的处罚策略。
     *
     * @param free               免罚次数，前几次只记数
     * @param basePenaltySeconds 首次处罚时长
     * @param maxPenaltySeconds  处罚上限
     * @param countWindowMs      计数窗口，超过就重新数
     * @param escalate           是否逐次翻倍
     */
    private record Policy(int free, long basePenaltySeconds, long maxPenaltySeconds,
                          long countWindowMs, boolean escalate) {
    }

    /** 一次违规的判定结果：blocked 为 true 时 remainingSeconds 是还要等多久 */
    public record Penalty(boolean blocked, long remainingSeconds, long penaltySeconds, int violationCount) {

        static final Penalty NONE = new Penalty(false, 0, 0, 0);
    }

    /** 后台展示用的一条封禁记录：userId 和 ip 只会有一个非空 */
    public record Blocked(String key, Long userId, String ip, long remainingSeconds,
                          int violationCount, String lastCategory) {
    }

    /** 查处罚状态，用于"还没提交就先拦下来"；不做任何写入 */
    public Penalty check(Long userId, String clientIp) {
        return merge(checkKey(userKey(userId)), checkKey(ipKey(clientIp)));
    }

    /**
     * 记一次不合规：用户维度和 IP 维度各记一次，返回更严格的那个结论。
     *
     * @param category 命中的类别（"词库""手机号"等），只用于后台展示，便于判断该补词还是该调规则
     * @return blocked 为 true 说明当前处于封禁期（可能是这次触发的，也可能是之前的）
     */
    public Penalty record(Long userId, String clientIp, String category) {
        return merge(recordKey(userKey(userId), USER_POLICY, category),
                recordKey(ipKey(clientIp), IP_POLICY, category));
    }

    /** 当前所有处于封禁期的记录，后台用来展示和手动解除 */
    public List<Blocked> blocked() {
        long now = clock.getAsLong();
        List<Blocked> result = new ArrayList<>();
        for (Map.Entry<String, ViolationState> entry : store.snapshot().entrySet()) {
            String key = entry.getKey();
            ViolationState state = entry.getValue();
            if (state == null || state.blockedUntil() <= now) {
                continue;
            }
            long remaining = toSeconds(state.blockedUntil() - now);
            if (key.startsWith(USER_PREFIX)) {
                Long userId = parseLong(key.substring(USER_PREFIX.length()));
                if (userId != null) {
                    result.add(new Blocked(key, userId, null, remaining, state.count(), state.lastCategory()));
                }
            } else if (key.startsWith(IP_PREFIX)) {
                result.add(new Blocked(key, null, key.substring(IP_PREFIX.length()), remaining,
                        state.count(), state.lastCategory()));
            }
        }
        result.sort(Comparator.comparingLong(Blocked::remainingSeconds).reversed());
        return result;
    }

    /**
     * 手动解除封禁（后台售后用：明确误判时能立刻放人）。
     * 只接受自己生成的两种 key，其它字符串一律当不存在。
     */
    public boolean clear(String key) {
        if (key == null || !(key.startsWith(USER_PREFIX) || key.startsWith(IP_PREFIX))) {
            return false;
        }
        return store.remove(key);
    }

    private Penalty checkKey(String key) {
        if (key == null) {
            return Penalty.NONE;
        }
        ViolationState state = store.get(key);
        if (state == null) {
            return Penalty.NONE;
        }
        long now = clock.getAsLong();
        if (state.blockedUntil() > now) {
            return new Penalty(true, toSeconds(state.blockedUntil() - now), 0, state.count());
        }
        return new Penalty(false, 0, 0, state.count());
    }

    /**
     * 读-改-写：存储层只提供整体读写，这里必须加锁，
     * 否则同一用户两次并发提交会各自读到旧值、把次数算重或算漏。
     * 调用频率很低（只在内容被拦时触发），全局锁的代价可以忽略。
     */
    private Penalty recordKey(String key, Policy policy, String category) {
        if (key == null) {
            return Penalty.NONE;
        }
        synchronized (this) {
            long now = clock.getAsLong();
            ViolationState state = store.get(key);
            int count = state == null ? 0 : state.count();
            long windowStart = state == null ? 0 : state.windowStart();
            long blockedUntil = state == null ? 0 : state.blockedUntil();
            String lastCategory = state == null ? "" : state.lastCategory();

            // 已在封禁期：直接返回现状。不累加、不延长，避免重复提交把封禁时间翻倍
            if (blockedUntil > now) {
                return new Penalty(true, toSeconds(blockedUntil - now), 0, count);
            }
            if (windowStart == 0 || now - windowStart > policy.countWindowMs()) {
                count = 0;
                windowStart = now;
            }
            count++;
            // 只有这次真的带上了类别才覆盖，避免把已有的诊断信息抹成空
            if (category != null && !category.isBlank()) {
                lastCategory = category;
            }

            if (count <= policy.free()) {
                store.put(key, new ViolationState(count, windowStart, 0, lastCategory));
                return new Penalty(false, 0, 0, count);
            }

            long penalty = penaltyFor(policy, count);
            blockedUntil = now + penalty * 1000;
            store.put(key, new ViolationState(count, windowStart, blockedUntil, lastCategory));
            log.info("评论违规达到 {} 次，封禁 {} 秒：{}", count, penalty, key);
            return new Penalty(true, penalty, penalty, count);
        }
    }

    /** 两个维度的结论合并：任一封禁就按等待更久的那个算，次数取较大值（只用于展示） */
    private static Penalty merge(Penalty byUser, Penalty byIp) {
        int count = Math.max(byUser.violationCount(), byIp.violationCount());
        if (!byUser.blocked() && !byIp.blocked()) {
            return new Penalty(false, 0, 0, count);
        }
        Penalty longer = byUser.remainingSeconds() >= byIp.remainingSeconds() ? byUser : byIp;
        return new Penalty(true, longer.remainingSeconds(), longer.penaltySeconds(), count);
    }

    /**
     * 第 4 次 10 分钟，之后每次翻倍，到上限就停。
     * 用循环而不是移位：次数很大时移位会溢出成负数。
     */
    private static long penaltyFor(Policy policy, int count) {
        if (!policy.escalate()) {
            return policy.basePenaltySeconds();
        }
        long seconds = policy.basePenaltySeconds();
        for (int i = policy.free() + 1; i < count && seconds < policy.maxPenaltySeconds(); i++) {
            seconds *= 2;
        }
        return Math.min(seconds, policy.maxPenaltySeconds());
    }

    /** 定期清掉已经解封且过了计数窗口的记录，避免存储只增不减 */
    @Scheduled(fixedDelayString = "${app.review-penalty-cleanup-ms:600000}", initialDelay = 600_000)
    public void cleanup() {
        long now = clock.getAsLong();
        for (Map.Entry<String, ViolationState> entry : store.snapshot().entrySet()) {
            ViolationState state = entry.getValue();
            if (state == null || state.blockedUntil() > now) {
                continue;
            }
            Policy policy = entry.getKey().startsWith(IP_PREFIX) ? IP_POLICY : USER_POLICY;
            if (now - state.windowStart() > policy.countWindowMs()) {
                store.remove(entry.getKey());
            }
        }
    }

    /**
     * 秒数转成人话：不足 1 分钟按 1 分钟算，超过 1 小时拆成"x 小时 y 分钟"。
     * 前台提示和后台列表共用同一套说法，避免两处措辞不一致。
     */
    public static String describeWait(long seconds) {
        long minutes = Math.max(1, (seconds + 59) / 60);
        if (minutes < 60) {
            return minutes + " 分钟";
        }
        long hours = minutes / 60;
        long rest = minutes % 60;
        return rest == 0 ? hours + " 小时" : hours + " 小时 " + rest + " 分钟";
    }

    private static String userKey(Long userId) {
        return userId == null ? null : USER_PREFIX + userId;
    }

    private static String ipKey(String clientIp) {
        if (clientIp == null || clientIp.isBlank()) {
            return null;
        }
        // IPv6 大小写不敏感，统一小写后再当 key，避免同一个地址记成两条
        return IP_PREFIX + clientIp.trim().toLowerCase(Locale.ROOT);
    }

    private static Long parseLong(String text) {
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static long toSeconds(long millis) {
        return (millis + 999) / 1000;
    }
}
