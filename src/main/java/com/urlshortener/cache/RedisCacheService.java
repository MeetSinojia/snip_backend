package com.urlshortener.cache;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * RedisCacheService  — UPDATED VERSION
 *
 * Changes vs original:
 *   • Added getRateLimitStatus(ip) — returns bucket state for the console UI
 *   • Added resetRateLimit(ip)    — clears bucket so devs can re-run the demo
 *
 * Replace the existing file at:
 *   src/main/java/com/urlshortener/cache/RedisCacheService.java
 */
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
     */
    public boolean isAllowed(String ip) {
        String key = RATE_LIMIT_KEY_PREFIX + ip;
        String value = redisTemplate.opsForValue().get(key);

        if (value == null) {
            redisTemplate.opsForValue().set(
                    key,
                    String.valueOf(rateLimitCapacity - 1),
                    rateLimitWindowSeconds,
                    TimeUnit.SECONDS
            );
            return true;
        }

        long remaining = Long.parseLong(value);

        if (remaining <= 0) {
            log.warn("Rate limit exceeded for IP={}", ip);
            return false;
        }

        redisTemplate.opsForValue().decrement(key);
        return true;
    }

    // ── NEW: Debug / Console helpers ─────────────────────────────────────────

    /**
     * Returns a rich snapshot of the token bucket for the given IP.
     * Called by the frontend Redis Console panel.
     */
    public Map<String, Object> getRateLimitStatus(String ip) {
        String key = RATE_LIMIT_KEY_PREFIX + ip;
        String value = redisTemplate.opsForValue().get(key);
        Long ttlSeconds = redisTemplate.getExpire(key, TimeUnit.SECONDS);

        long remaining  = value != null ? Long.parseLong(value) : rateLimitCapacity;
        long used       = rateLimitCapacity - remaining;
        long ttl        = ttlSeconds != null && ttlSeconds >= 0 ? ttlSeconds : rateLimitWindowSeconds;

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ip",               ip);
        result.put("redisKey",         key);
        result.put("tokensRemaining",  remaining);
        result.put("tokensUsed",       used);
        result.put("capacity",         rateLimitCapacity);
        result.put("windowSeconds",    rateLimitWindowSeconds);
        result.put("ttlSeconds",       ttl);
        result.put("bucketExists",     value != null);
        result.put("limitExceeded",    remaining <= 0);
        return result;
    }

    /**
     * Deletes the rate-limit bucket for the given IP.
     * Used by the "Reset" button in the Rate Limit Simulator panel.
     */
    public void resetRateLimit(String ip) {
        String key = RATE_LIMIT_KEY_PREFIX + ip;
        redisTemplate.delete(key);
        log.info("Rate limit reset for IP={}", ip);
    }
}