package com.urlshortener.service;

import com.urlshortener.cache.RedisCacheService;
import com.urlshortener.exception.UrlNotFoundException;
import com.urlshortener.model.Url;
import com.urlshortener.model.UrlRequest;
import com.urlshortener.model.UrlResponse;
import com.urlshortener.repository.UrlRepository;
import com.urlshortener.sharding.ShardContext;
import com.urlshortener.sharding.ShardingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
public class UrlServiceImpl implements UrlService {

    private final UrlRepository             urlRepository;
    private final RedisCacheService         cacheService;
    private final Base62Service             base62Service;
    private final SnowflakeIdGenerator      snowflakeIdGenerator;
    private final ShardingService           shardingService;
    private final PlatformTransactionManager txManager;

    @Value("${app.base-url}")
    private String baseUrl;

    @Value("${app.cache.url-ttl-hours}")
    private long defaultTtlHours;

    // ── Shorten ──────────────────────────────────────────────────────────────
    //
    // Old flow (2 saves):  INSERT "pending" → get ID → UPDATE shortCode
    // New flow (1 save):   Snowflake ID → Base62 shortCode → shard → INSERT
    //
    // ShardContext MUST be set before TransactionTemplate.execute() is called,
    // because AbstractRoutingDataSource.determineCurrentLookupKey() fires when
    // the connection is acquired at the start of the transaction.

    @Override
    public UrlResponse shorten(UrlRequest request) {
        long id          = snowflakeIdGenerator.nextId();
        String shortCode = base62Service.encode(id);
        int shardIndex   = shardingService.getShardIndex(shortCode);

        ShardContext.set(shardIndex);
        try {
            TransactionTemplate tx = new TransactionTemplate(txManager);
            Url saved = tx.execute(status -> {
                Url url = Url.builder()
                        .id(id)
                        .originalUrl(request.getOriginalUrl())
                        .shortCode(shortCode)
                        .expiresAt(resolveExpiry(request.getExpiryHours()))
                        .build();
                return urlRepository.save(url);
            });

            cacheService.cacheUrl(shortCode, request.getOriginalUrl());
            log.info("Shortened URL id={} shortCode={} shard={}", id, shortCode, shardIndex);
            return buildResponse(saved);

        } finally {
            ShardContext.clear();
        }
    }

    // ── Resolve ──────────────────────────────────────────────────────────────

    @Override
    public String resolve(String shortCode) {
        // Fast path: Redis HIT — no DB, no shard context needed
        var cached = cacheService.getCachedUrl(shortCode);
        if (cached.isPresent()) {
            cacheService.incrementClickCount(shortCode);
            log.debug("Cache HIT shortCode={}", shortCode);
            return cached.get();
        }

        // Slow path: query correct shard REPLICA (readOnly=true)
        log.debug("Cache MISS shortCode={} → querying replica", shortCode);
        int shardIndex = shardingService.getShardIndex(shortCode);
        log.info("SHORTEN → shortCode={} shard={}", shortCode, shardIndex);
        ShardContext.set(shardIndex);
        try {
            TransactionTemplate tx = new TransactionTemplate(txManager);
            tx.setReadOnly(true);  // ShardRoutingDataSource routes to replica
            tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

            String originalUrl = tx.execute(status -> {
                Url url = urlRepository.findByShortCodeAndIsActiveTrue(shortCode)
                        .orElseThrow(() -> new UrlNotFoundException(shortCode));

                if (url.isExpired()) {
                    log.warn("Expired URL accessed shortCode={}", shortCode);
                    throw new UrlNotFoundException(shortCode);
                }
                return url.getOriginalUrl();
            });

            cacheService.cacheUrl(shortCode, originalUrl);   // cache-aside refill
            cacheService.incrementClickCount(shortCode);
            return originalUrl;

        } finally {
            ShardContext.clear();
        }
    }

    // ── Deactivate ────────────────────────────────────────────────────────────

    @Override
    public void deactivate(String shortCode) {
        int shardIndex = shardingService.getShardIndex(shortCode);
        ShardContext.set(shardIndex);
        try {
            TransactionTemplate tx = new TransactionTemplate(txManager);
            tx.execute(status -> {
                Url url = urlRepository.findByShortCodeAndIsActiveTrue(shortCode)
                        .orElseThrow(() -> new UrlNotFoundException(shortCode));
                url.setIsActive(false);
                urlRepository.save(url);
                return null;
            });

            cacheService.evictUrl(shortCode);
            log.info("Deactivated shortCode={} shard={}", shortCode, shardIndex);

        } finally {
            ShardContext.clear();
        }
    }

    // ── Scheduled cleanup across ALL shards ──────────────────────────────────

    @Scheduled(fixedRateString = "PT1H")
    public void cleanupExpiredUrls() {
        for (int shard = 0; shard < shardingService.getShardCount(); shard++) {
            final int shardIndex = shard;
            ShardContext.set(shardIndex);
            try {
                TransactionTemplate tx = new TransactionTemplate(txManager);
                Integer count = tx.execute(status ->
                        urlRepository.deactivateExpiredUrls(LocalDateTime.now()));
                if (count != null && count > 0) {
                    log.info("Cleanup: deactivated {} expired URLs on shard={}", count, shardIndex);
                }
            } finally {
                ShardContext.clear();
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private LocalDateTime resolveExpiry(Integer requestedHours) {
        long hours = (requestedHours != null && requestedHours > 0)
                ? requestedHours
                : defaultTtlHours;
        return LocalDateTime.now().plusHours(hours);
    }

    private UrlResponse buildResponse(Url url) {
        return UrlResponse.builder()
                .shortCode(url.getShortCode())
                .shortUrl(baseUrl + "/" + url.getShortCode())
                .originalUrl(url.getOriginalUrl())
                .createdAt(url.getCreatedAt())
                .expiresAt(url.getExpiresAt())
                .build();
    }
}