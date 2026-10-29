package com.codereviewx.config;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import io.github.bucket4j.Bucket;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * In-memory token buckets keyed by operation and client (IP or e-mail). Suitable for a single instance;
 * a multi-instance deployment needs a shared store (Bucket4j supports JCache/Redis back ends).
 */
@Component
public class RateLimiter {
    private static final int MAX_KEYS = 50_000;
    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    /** Throws 429 when {@code key} has used up {@code perMinute} requests in the current minute. */
    public void check(String operation, String key, int perMinute) {
        if (perMinute <= 0) return;
        if (buckets.size() > MAX_KEYS) buckets.clear(); // bound memory under key-spraying
        Bucket b = buckets.computeIfAbsent(operation + '|' + key, k -> Bucket.builder()
            .addLimit(l -> l.capacity(perMinute).refillGreedy(perMinute, Duration.ofMinutes(1)))
            .build());
        if (!b.tryConsume(1)) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "rate_limited",
                "Too many attempts. Wait a minute and try again.");
        }
    }

    /** Test hook. */
    public void reset() { buckets.clear(); }
}
