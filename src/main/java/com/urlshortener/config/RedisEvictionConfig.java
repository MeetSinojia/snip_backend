package com.urlshortener.config;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

/**
 * RedisEvictionConfig — configures Redis eviction policy and memory limits at startup.
 *
 * ┌─────────────────────────────────────────────────────────────────────────┐
 * │  Eviction strategy for this URL shortener:                             │
 * │                                                                         │
 * │  POLICY  │  volatile-lfu                                                │
 * │  ─────────────────────────────────────────────────────────────────────  │
 * │  • "volatile" = only evict keys that have a TTL set                     │
 * │  • "lfu"      = evict the Least Frequently Used key first               │
 * │                                                                         │
 * │  Why volatile-lfu over allkeys-lru?                                     │
 * │  ─────────────────────────────────────────────────────────────────────  │
 * │  1. All URL keys (url:*) and click counters (clicks:*) already have     │
 * │     TTL set by RedisCacheService — so volatile-* covers everything.     │
 * │  2. LFU keeps HOT URLs cached longer (popular short codes), while       │
 * │     LRU would blindly evict anything untouched for a while.             │
 * │  3. Rate-limit buckets (rate:*) also have TTL and should be evictable.  │
 * │                                                                         │
 * │  TTL recap (from application.yml):                                      │
 * │    url:*     → 24h     (cache-aside for redirects)                      │
 * │    clicks:*  → 168h    (7 days of click analytics)                      │
 * │    rate:*    → 60s     (token-bucket window)                            │
 * └─────────────────────────────────────────────────────────────────────────┘
 *
 * CONFIG SET is idempotent — safe to call on every restart.
 * Override via:  app.redis.eviction-policy and app.redis.max-memory in application.yml
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class RedisEvictionConfig {

    private final RedisConnectionFactory redisConnectionFactory;

    /**
     * volatile-lfu  → evict TTL-bearing keys by LFU when memory is full.
     * Change to "allkeys-lfu" if you add non-TTL keys and still want eviction.
     * Change to "volatile-lru" if recency matters more than frequency.
     */
    @Value("${app.redis.eviction-policy:volatile-lfu}")
    private String evictionPolicy;

    /**
     * Maximum Redis memory before eviction kicks in.
     * Set to 0 to use Redis's own maxmemory setting from redis.conf.
     * Example values: "256mb", "1gb", "0" (no limit / use redis.conf)
     */
    @Value("${app.redis.max-memory:256mb}")
    private String maxMemory;

    @PostConstruct
    public void configureEvictionPolicy() {
        try {
            redisConnectionFactory.getConnection().serverCommands().setConfig("maxmemory-policy", evictionPolicy);
            log.info("Redis maxmemory-policy set to '{}'", evictionPolicy);

            if (!"0".equals(maxMemory)) {
                redisConnectionFactory.getConnection().serverCommands().setConfig("maxmemory", maxMemory);
                log.info("Redis maxmemory set to '{}'", maxMemory);
            }

            // Enable LFU decay time (how quickly access frequency decays over time).
            // 10 = counter halved every ~1 minute. Lower = faster decay (more adaptive).
            // Only relevant when using an LFU policy; ignored otherwise.
            redisConnectionFactory.getConnection().serverCommands().setConfig("lfu-decay-time", "10");

            // LFU log factor: lower = more granular frequency counting at low access rates.
            // Default is 10. Set to 5 for URLs (lots of low-frequency codes, few hot ones).
            redisConnectionFactory.getConnection().serverCommands().setConfig("lfu-log-factor", "5");

            log.info("Redis LFU parameters configured (decay-time=10, log-factor=5)");

        } catch (Exception e) {
            // Non-fatal: Redis server may not support CONFIG SET (e.g., Redis Cluster managed mode)
            log.warn("Could not configure Redis eviction policy via CONFIG SET: {} — " +
                     "ensure redis.conf sets maxmemory-policy={}", e.getMessage(), evictionPolicy);
        }
    }
}