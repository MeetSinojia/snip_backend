package com.urlshortener.service;

import com.urlshortener.cache.RedisCacheService;
import com.urlshortener.exception.UrlNotFoundException;
import com.urlshortener.model.Url;
import com.urlshortener.model.UrlRequest;
import com.urlshortener.model.UrlResponse;
import com.urlshortener.repository.UrlRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
public class UrlServiceImpl implements UrlService {

    private final UrlRepository      urlRepository;
    private final RedisCacheService  cacheService;
    private final Base62Service      base62Service;

    @Value("${app.base-url}")
    private String baseUrl;

    @Value("${app.cache.url-ttl-hours}")
    private long defaultTtlHours;

    // ── Shorten ──────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public UrlResponse shorten(UrlRequest request) {
        LocalDateTime expiresAt = resolveExpiry(request.getExpiryHours());

        // Persist to PostgreSQL first to get the auto-generated ID
        Url url = Url.builder()
                .originalUrl(request.getOriginalUrl())
                .shortCode("pending")   // temporary placeholder before ID is known
                .expiresAt(expiresAt)
                .build();

        url = urlRepository.save(url);

        // Encode the DB-generated ID to Base62 short code
        String shortCode = base62Service.encode(url.getId());
        url.setShortCode(shortCode);
        url = urlRepository.save(url);

        // Populate Redis cache so first redirect is instant
        cacheService.cacheUrl(shortCode, request.getOriginalUrl());

        log.info("Shortened URL id={} shortCode={} expiresAt={}", url.getId(), shortCode, expiresAt);

        return buildResponse(url);
    }

    // ── Resolve ──────────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public String resolve(String shortCode) {
        // 1. Cache hit — fast path
        var cached = cacheService.getCachedUrl(shortCode);
        if (cached.isPresent()) {
            cacheService.incrementClickCount(shortCode);
            log.debug("Cache HIT for shortCode={}", shortCode);
            return cached.get();
        }

        // 2. Cache miss — query PostgreSQL
        log.debug("Cache MISS for shortCode={} — querying DB", shortCode);
        Url url = urlRepository.findByShortCodeAndIsActiveTrue(shortCode)
                .orElseThrow(() -> new UrlNotFoundException(shortCode));

        // Guard: treat expired URLs as not found
        if (url.isExpired()) {
            log.warn("Attempted access to expired URL shortCode={}", shortCode);
            throw new UrlNotFoundException(shortCode);
        }

        // 3. Re-populate cache (cache-aside)
        cacheService.cacheUrl(shortCode, url.getOriginalUrl());
        cacheService.incrementClickCount(shortCode);

        return url.getOriginalUrl();
    }

    // ── Deactivate ────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public void deactivate(String shortCode) {
        Url url = urlRepository.findByShortCodeAndIsActiveTrue(shortCode)
                .orElseThrow(() -> new UrlNotFoundException(shortCode));

        url.setIsActive(false);
        urlRepository.save(url);
        cacheService.evictUrl(shortCode);

        log.info("Deactivated URL shortCode={}", shortCode);
    }

    // ── Scheduled cleanup ─────────────────────────────────────────────────────

    /**
     * Runs every hour to soft-delete expired URLs in bulk.
     * Keeps the DB clean without impacting request latency.
     */
    @Scheduled(fixedRateString = "PT1H")
    @Transactional
    public void cleanupExpiredUrls() {
        int deactivated = urlRepository.deactivateExpiredUrls(LocalDateTime.now());
        if (deactivated > 0) {
            log.info("Cleanup job deactivated {} expired URLs", deactivated);
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
