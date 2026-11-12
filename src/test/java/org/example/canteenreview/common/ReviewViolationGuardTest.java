package org.example.canteenreview.common;

import org.example.canteenreview.common.violation.InMemoryViolationStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 评论违规阶梯处罚：
 * 用户维度前三次只记数、第 4 次起 10 分钟并逐次翻倍（封顶 12 小时）；
 * IP 维度门槛更高、时长固定，只做换号兜底。
 *
 * <p>用假时钟推进时间，否则"解封后再违规翻倍"这条分支根本跑不到。
 */
class ReviewViolationGuardTest {

    private static final long USER = 7L;
    private static final String IP = "10.0.0.8";

    private final AtomicLong now = new AtomicLong(1_700_000_000_000L);
    private InMemoryViolationStore store;
    private ReviewViolationGuard guard;

    @BeforeEach
    void setUp() {
        now.set(1_700_000_000_000L);
        store = new InMemoryViolationStore();
        guard = new ReviewViolationGuard(store, now::get);
    }

    @Test
    void stateLivesInStoreSoAnotherInstanceSeesIt() {
        // 状态不在 Guard 对象里，而在 ViolationStore 里：
        // 换成 Redis 实现后，多实例就能共享同一份处罚记录
        recordTimes(4);
        ReviewViolationGuard other = new ReviewViolationGuard(store, now::get);
        assertTrue(other.check(USER, null).blocked(), "换一个 Guard 实例也应该认得这条封禁");
    }

    @Test
    void firstThreeViolationsDoNotBlock() {
        for (int i = 1; i <= 3; i++) {
            ReviewViolationGuard.Penalty penalty = guard.record(USER, null, "词库");
            assertFalse(penalty.blocked(), "第 " + i + " 次不该封禁");
            assertEquals(i, penalty.violationCount());
            assertFalse(guard.check(USER, null).blocked());
        }
    }

    @Test
    void fourthViolationBlocksTenMinutes() {
        recordTimes(3);

        ReviewViolationGuard.Penalty penalty = guard.record(USER, null, "词库");
        assertTrue(penalty.blocked());
        assertEquals(600, penalty.penaltySeconds());
        assertEquals(600, guard.check(USER, null).remainingSeconds());
    }

    @Test
    void penaltyDoublesEachTime() {
        recordTimes(3);

        assertEquals(600, guard.record(USER, null, "词库").penaltySeconds(), "第 4 次");
        elapse(600);
        assertEquals(1200, guard.record(USER, null, "词库").penaltySeconds(), "第 5 次");
        elapse(1200);
        assertEquals(2400, guard.record(USER, null, "词库").penaltySeconds(), "第 6 次");
        elapse(2400);
        assertEquals(4800, guard.record(USER, null, "词库").penaltySeconds(), "第 7 次");
    }

    @Test
    void penaltyIsCapped() {
        recordTimes(3);

        long penalty = guard.record(USER, null, "词库").penaltySeconds();
        for (int i = 0; i < 10; i++) {
            elapse(penalty);
            penalty = guard.record(USER, null, "词库").penaltySeconds();
        }
        assertEquals(Duration.ofHours(12).toSeconds(), penalty, "无限翻倍等于永久封号，必须有上限");
    }

    @Test
    void recordingWhileBlockedDoesNotExtend() {
        recordTimes(4);
        long before = guard.check(USER, null).remainingSeconds();

        ReviewViolationGuard.Penalty again = guard.record(USER, null, "词库");
        assertEquals(4, again.violationCount(), "封禁期内不该继续累加次数");
        assertEquals(before, again.remainingSeconds(), "被禁时重复提交不能改变剩余时间");
    }

    @Test
    void counterResetsAfterWindow() {
        recordTimes(3);
        elapse(Duration.ofDays(7).plusMinutes(1).toSeconds());

        ReviewViolationGuard.Penalty penalty = guard.record(USER, null, "词库");
        assertEquals(1, penalty.violationCount(), "超过计数窗口应重新开始数");
        assertFalse(penalty.blocked());
    }

    @Test
    void ipDimensionBlocksAfterTenViolations() {
        // 每次换一个账号：换号重刷正是 IP 维度要兜的场景
        for (int i = 0; i < 10; i++) {
            assertFalse(guard.record(100L + i, IP, "词库").blocked(), "IP 维度第 " + (i + 1) + " 次不该封");
        }
        ReviewViolationGuard.Penalty penalty = guard.record(200L, IP, "词库");
        assertTrue(penalty.blocked(), "第 11 次应触发 IP 封禁");
        assertEquals(600, penalty.penaltySeconds(), "IP 维度固定 10 分钟，不翻倍");
    }

    @Test
    void ipPenaltyDoesNotLeakToOtherIps() {
        for (int i = 0; i < 11; i++) {
            guard.record(100L + i, "10.0.0.9", "手机号");
        }
        assertTrue(guard.check(999L, "10.0.0.9").blocked(), "同一 IP 的新账号也要被挡住");
        assertFalse(guard.check(999L, "10.0.0.10").blocked(), "别的 IP 不该被连带");
    }

    @Test
    void usersAreIsolated() {
        recordTimes(4);

        assertTrue(guard.check(USER, null).blocked());
        assertFalse(guard.check(9L, null).blocked(), "别人的违规不能算到这个用户头上");
        assertFalse(guard.check(null, null).blocked(), "未登录调用要安全返回");
        assertFalse(guard.record(null, null, "词库").blocked());
    }

    @Test
    void adminCanClearPenalty() {
        recordTimes(4);

        var blocked = guard.blocked();
        assertEquals(1, blocked.size());
        assertEquals("user:" + USER, blocked.get(0).key());
        assertEquals(USER, blocked.get(0).userId());
        assertEquals(600, blocked.get(0).remainingSeconds());
        assertEquals("词库", blocked.get(0).lastCategory(), "后台要能看到最近命中的是什么类别");

        assertTrue(guard.clear("user:" + USER));
        assertFalse(guard.check(USER, null).blocked(), "后台解除后应立刻恢复");
        assertTrue(guard.blocked().isEmpty());
    }

    @Test
    void clearRejectsUnknownKeys() {
        assertFalse(guard.clear("user:999"));
        assertFalse(guard.clear("whatever"));
        assertFalse(guard.clear(null));
    }

    @Test
    void waitIsDescribedInHumanTerms() {
        assertEquals("1 分钟", ReviewViolationGuard.describeWait(0));
        assertEquals("10 分钟", ReviewViolationGuard.describeWait(600));
        assertEquals("1 小时", ReviewViolationGuard.describeWait(3600));
        assertEquals("1 小时 1 分钟", ReviewViolationGuard.describeWait(3660));
    }

    private void elapse(long seconds) {
        now.addAndGet(seconds * 1000);
    }

    private void recordTimes(int times) {
        for (int i = 0; i < times; i++) {
            guard.record(USER, null, "词库");
        }
    }
}
