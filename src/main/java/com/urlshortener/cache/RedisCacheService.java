package com.urlshortener.cache;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class RedisCacheService {

    private static final String URL_KEY_PREFIX        = "url:";
    private static final String CLICK_COUNT_PREFIX    = "clicks:";
    private static final String RATE_LIMIT_KEY_PREFIX = "rate:";

    private final StringRedisTemplate redisTemplate;

    @Value("${app.cache.url-ttl-hours}")
    private long urlTtlHours;

    @Value("${app.cache.click-count-ttl-hours}")
    private long clickCountTtlHours;

    @Value("${app.rate-limit.capacity}")
    private long rateLimitCapacity;

    @Value("${app.rate-limit.window-seconds}")
    private long rateLimitWindowSeconds;

    // ── URL caching ─────────────────────────────────────────────────────────

    public void cacheUrl(String shortCode, String originalUrl) {
        String key = URL_KEY_PREFIX + shortCode;
        redisTemplate.opsForValue().set(key, originalUrl, urlTtlHours, TimeUnit.HOURS);
        log.debug("Cached URL for shortCode={} with TTL={}h", shortCode, urlTtlHours);
    }

    public Optional<String> getCachedUrl(String shortCode) {
        String value = redisTemplate.opsForValue().get(URL_KEY_PREFIX + shortCode);
        return Optional.ofNullable(value);
    }

    public void evictUrl(String shortCode) {
        redisTemplate.delete(URL_KEY_PREFIX + shortCode);
        log.debug("Evicted cache for shortCode={}", shortCode);
    }

    // ── Click counting ───────────────────────────────────────────────────────

    public long incrementClickCount(String shortCode) {
        String key = CLICK_COUNT_PREFIX + shortCode;
        Long count = redisTemplate.opsForValue().increment(key);

        // Set TTL only on first increment (when count == 1)
        if (count != null && count == 1L) {
            redisTemplate.expire(key, clickCountTtlHours, TimeUnit.HOURS);
        }

        return count != null ? count : 0L;
    }

    public long getClickCount(String shortCode) {
        String value = redisTemplate.opsForValue().get(CLICK_COUNT_PREFIX + shortCode);
        return value != null ? Long.parseLong(value) : 0L;
    }

    // ── Token bucket rate limiting ───────────────────────────────────────────

    /**
     * Checks whether the given IP has remaining tokens in its bucket.
     * Uses Redis DECR with expiry to implement a fixed-window token bucket.
     *
     * @return true if request is allowed, false if rate limit exceeded
     */
    public boolean isAllowed(String ip) {
        String key = RATE_LIMIT_KEY_PREFIX + ip;

        Long remaining = redisTemplate.opsForValue().decrement(key);

        if (remaining == null) {
            // Key doesn't exist — initialize bucket at capacity - 1 (we already consumed one token)
            redisTemplate.opsForValue().set(key, String.valueOf(rateLimitCapacity - 1),
                    rateLimitWindowSeconds, TimeUnit.SECONDS);
            return true;
        }

        if (remaining < 0) {
            // Bucket exhausted — reset to 0 to prevent unbounded negative values
            redisTemplate.opsForValue().set(key, "0",
                    rateLimitWindowSeconds, TimeUnit.SECONDS);
            log.warn("Rate limit exceeded for IP={}", ip);
            return false;
        }

        return true;
    }
}
