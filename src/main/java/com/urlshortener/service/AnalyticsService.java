package com.urlshortener.service;

import com.urlshortener.cache.RedisCacheService;
import com.urlshortener.exception.UrlNotFoundException;
import com.urlshortener.model.AnalyticsResponse;
import com.urlshortener.model.Url;
import com.urlshortener.repository.UrlRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class AnalyticsService {

    private final UrlRepository     urlRepository;
    private final RedisCacheService cacheService;

    /**
     * Returns analytics for a given short code.
     * Click count is sourced from Redis (real-time counter).
     * URL metadata is sourced from PostgreSQL.
     */
    @Transactional(readOnly = true)
    public AnalyticsResponse getAnalytics(String shortCode) {
        Url url = urlRepository.findByShortCodeAndIsActiveTrue(shortCode)
                .orElseThrow(() -> new UrlNotFoundException(shortCode));

        long totalClicks = cacheService.getClickCount(shortCode);

        log.debug("Analytics requested for shortCode={} clicks={}", shortCode, totalClicks);

        return AnalyticsResponse.builder()
                .shortCode(shortCode)
                .originalUrl(url.getOriginalUrl())
                .totalClicks(totalClicks)
                .createdAt(url.getCreatedAt())
                .expiresAt(url.getExpiresAt())
                .build();
    }
}
