package com.webssh.task;

import com.webssh.config.ResourceGovernanceProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 用户级资源配额守卫。
 * <p>
 * 提供轻量的并发令牌与滑动窗口限流，避免单个用户把线程池与长任务入口全部占满。
 * </p>
 */
@Component
public class UserResourceGovernor {

    private final ResourceGovernanceProperties properties;
    private final ConcurrentMap<String, AtomicInteger> concurrentCounters = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, SlidingWindowLimiter> rateLimiters = new ConcurrentHashMap<>();

    public UserResourceGovernor(ResourceGovernanceProperties properties) {
        this.properties = properties;
    }

    public Permit tryAcquireWsShell(String userKey) {
        return tryAcquire("ws-shell", userKey, properties.getWsShellPerUser());
    }

    public Permit tryAcquireBotCommand(String userKey) {
        return tryAcquire("bot-command", userKey, properties.getBotCommandPerUser());
    }

    public Permit tryAcquireAiTask(String userKey) {
        return tryAcquire("ai-task", userKey, properties.getAiTaskPerUser());
    }

    public Permit tryAcquireQqEvent(String userKey) {
        return tryAcquire("qq-event", userKey, properties.getQqEventPerUser());
    }

    public Permit tryAcquireWechatEvent(String userKey) {
        return tryAcquire("wechat-event", userKey, properties.getWechatEventPerUser());
    }

    public boolean allowBotMessage(String userKey) {
        return tryConsume("bot-message", userKey,
                properties.getBotMessageRateLimit(),
                properties.getBotMessageRateWindow().toMillis());
    }

    public boolean allowQqMessage(String userKey) {
        return tryConsume("qq-message", userKey,
                properties.getQqMessageRateLimit(),
                properties.getQqMessageRateWindow().toMillis());
    }

    public boolean allowWechatMessage(String userKey) {
        return tryConsume("wechat-message", userKey,
                properties.getWechatMessageRateLimit(),
                properties.getWechatMessageRateWindow().toMillis());
    }

    private Permit tryAcquire(String resourceType, String userKey, int maxConcurrent) {
        if (maxConcurrent <= 0 || userKey == null || userKey.isBlank()) {
            return Permit.noop();
        }
        String key = resourceType + ":" + userKey;
        AtomicInteger counter = concurrentCounters.computeIfAbsent(key, ignored -> new AtomicInteger());
        while (true) {
            int current = counter.get();
            if (current >= maxConcurrent) {
                return Permit.rejected();
            }
            if (counter.compareAndSet(current, current + 1)) {
                return Permit.granted(() -> releaseCounter(key, counter));
            }
        }
    }

    private void releaseCounter(String key, AtomicInteger counter) {
        while (true) {
            int current = counter.get();
            if (current <= 0) {
                concurrentCounters.remove(key, counter);
                return;
            }
            int next = current - 1;
            if (counter.compareAndSet(current, next)) {
                if (next == 0) {
                    concurrentCounters.remove(key, counter);
                }
                return;
            }
        }
    }

    private boolean tryConsume(String resourceType, String userKey, int limit, long windowMs) {
        if (limit <= 0 || windowMs <= 0 || userKey == null || userKey.isBlank()) {
            return true;
        }
        String key = resourceType + ":" + userKey;
        SlidingWindowLimiter limiter = rateLimiters.computeIfAbsent(key,
                ignored -> new SlidingWindowLimiter(limit, windowMs));
        return limiter.tryAcquire(System.currentTimeMillis());
    }

    /**
     * 可关闭的并发令牌。
     */
    public static final class Permit implements AutoCloseable {
        private static final Permit REJECTED = new Permit(false, null);
        private static final Permit NOOP = new Permit(true, null);

        private final boolean granted;
        private final Runnable releaser;
        private final AtomicBoolean closed = new AtomicBoolean(false);

        private Permit(boolean granted, Runnable releaser) {
            this.granted = granted;
            this.releaser = releaser;
        }

        public static Permit rejected() {
            return REJECTED;
        }

        public static Permit noop() {
            return NOOP;
        }

        public static Permit granted(Runnable releaser) {
            return new Permit(true, releaser);
        }

        public boolean granted() {
            return granted;
        }

        @Override
        public void close() {
            if (!granted || releaser == null || !closed.compareAndSet(false, true)) {
                return;
            }
            releaser.run();
        }
    }

    /**
     * 基于滑动时间窗口的简单限流器。
     */
    private static final class SlidingWindowLimiter {
        private final int limit;
        private final long windowMs;
        private final Deque<Long> timestamps = new ArrayDeque<>();

        private SlidingWindowLimiter(int limit, long windowMs) {
            this.limit = limit;
            this.windowMs = windowMs;
        }

        private synchronized boolean tryAcquire(long now) {
            long cutoff = now - windowMs;
            while (!timestamps.isEmpty() && timestamps.peekFirst() <= cutoff) {
                timestamps.pollFirst();
            }
            if (timestamps.size() >= limit) {
                return false;
            }
            timestamps.addLast(now);
            return true;
        }
    }
}
