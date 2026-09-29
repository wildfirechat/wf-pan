package com.wildfirechat.pan.service.auth;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 登录失败限流：同一 IP + 用户名在窗口期内失败次数达到上限后拒绝登录。
 */
@Component
public class LoginThrottle {

    static final int MAX_FAILURES = 5;
    static final Duration WINDOW = Duration.ofMinutes(15);

    private final Cache<String, AtomicInteger> failures = Caffeine.newBuilder()
        .expireAfterWrite(WINDOW)
        .maximumSize(10_000)
        .build();

    public boolean isBlocked(String key) {
        AtomicInteger count = failures.getIfPresent(key);
        return count != null && count.get() >= MAX_FAILURES;
    }

    public void recordFailure(String key) {
        failures.get(key, k -> new AtomicInteger()).incrementAndGet();
    }

    public void reset(String key) {
        failures.invalidate(key);
    }
}
