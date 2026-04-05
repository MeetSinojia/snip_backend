package com.urlshortener;

import com.urlshortener.cache.RedisCacheService;
import com.urlshortener.exception.UrlNotFoundException;
import com.urlshortener.model.Url;
import com.urlshortener.model.UrlRequest;
import com.urlshortener.model.UrlResponse;
import com.urlshortener.repository.UrlRepository;
import com.urlshortener.service.Base62Service;
import com.urlshortener.service.UrlServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UrlServiceTest {

    @Mock private UrlRepository     urlRepository;
    @Mock private RedisCacheService cacheService;
    @Mock private Base62Service     base62Service;

    @InjectMocks
    private UrlServiceImpl urlService;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(urlService, "baseUrl", "http://localhost:8080");
        ReflectionTestUtils.setField(urlService, "defaultTtlHours", 24L);
    }

    // ── shorten() ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("shorten() persists URL and returns short code")
    void shorten_validRequest_returnsResponse() {
        UrlRequest request = new UrlRequest("https://example.com/very/long/path", null);

        Url savedUrl = Url.builder()
                .id(1L)
                .originalUrl("https://example.com/very/long/path")
                .shortCode("pending")
                .createdAt(LocalDateTime.now())
                .expiresAt(LocalDateTime.now().plusHours(24))
                .build();

        when(urlRepository.save(any(Url.class))).thenReturn(savedUrl);
        when(base62Service.encode(1L)).thenReturn("abcdef");

        // Second save with shortCode set
        Url finalUrl = Url.builder()
                .id(1L)
                .originalUrl("https://example.com/very/long/path")
                .shortCode("abcdef")
                .createdAt(savedUrl.getCreatedAt())
                .expiresAt(savedUrl.getExpiresAt())
                .build();
        when(urlRepository.save(argThat(u -> "abcdef".equals(u.getShortCode()))))
                .thenReturn(finalUrl);

        UrlResponse response = urlService.shorten(request);

        assertThat(response.getShortCode()).isEqualTo("abcdef");
        assertThat(response.getShortUrl()).isEqualTo("http://localhost:8080/abcdef");
        assertThat(response.getOriginalUrl()).isEqualTo("https://example.com/very/long/path");
        verify(cacheService).cacheUrl(eq("abcdef"), eq("https://example.com/very/long/path"));
    }

    // ── resolve() — cache hit ─────────────────────────────────────────────────

    @Test
    @DisplayName("resolve() returns cached URL on cache hit without hitting DB")
    void resolve_cacheHit_returnsUrlWithoutDbQuery() {
        when(cacheService.getCachedUrl("abcdef"))
                .thenReturn(Optional.of("https://example.com"));

        String result = urlService.resolve("abcdef");

        assertThat(result).isEqualTo("https://example.com");
        verify(urlRepository, never()).findByShortCodeAndIsActiveTrue(any());
        verify(cacheService).incrementClickCount("abcdef");
    }

    // ── resolve() — cache miss ────────────────────────────────────────────────

    @Test
    @DisplayName("resolve() queries DB on cache miss and re-populates cache")
    void resolve_cacheMiss_queriesDbAndPopulatesCache() {
        when(cacheService.getCachedUrl("abcdef")).thenReturn(Optional.empty());

        Url url = Url.builder()
                .id(1L)
                .originalUrl("https://example.com")
                .shortCode("abcdef")
                .isActive(true)
                .expiresAt(LocalDateTime.now().plusHours(24))
                .build();

        when(urlRepository.findByShortCodeAndIsActiveTrue("abcdef"))
                .thenReturn(Optional.of(url));

        String result = urlService.resolve("abcdef");

        assertThat(result).isEqualTo("https://example.com");
        verify(cacheService).cacheUrl("abcdef", "https://example.com");
        verify(cacheService).incrementClickCount("abcdef");
    }

    // ── resolve() — not found ─────────────────────────────────────────────────

    @Test
    @DisplayName("resolve() throws UrlNotFoundException for unknown short code")
    void resolve_unknownCode_throwsNotFoundException() {
        when(cacheService.getCachedUrl("unknown")).thenReturn(Optional.empty());
        when(urlRepository.findByShortCodeAndIsActiveTrue("unknown"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> urlService.resolve("unknown"))
                .isInstanceOf(UrlNotFoundException.class)
                .hasMessageContaining("unknown");
    }

    // ── resolve() — expired URL ───────────────────────────────────────────────

    @Test
    @DisplayName("resolve() throws UrlNotFoundException for expired URL")
    void resolve_expiredUrl_throwsNotFoundException() {
        when(cacheService.getCachedUrl("abcdef")).thenReturn(Optional.empty());

        Url expiredUrl = Url.builder()
                .id(1L)
                .originalUrl("https://example.com")
                .shortCode("abcdef")
                .isActive(true)
                .expiresAt(LocalDateTime.now().minusHours(1))  // already expired
                .build();

        when(urlRepository.findByShortCodeAndIsActiveTrue("abcdef"))
                .thenReturn(Optional.of(expiredUrl));

        assertThatThrownBy(() -> urlService.resolve("abcdef"))
                .isInstanceOf(UrlNotFoundException.class);
    }

    // ── deactivate() ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("deactivate() marks URL inactive and evicts from cache")
    void deactivate_existingUrl_deactivatesAndEvicts() {
        Url url = Url.builder()
                .id(1L)
                .shortCode("abcdef")
                .isActive(true)
                .build();

        when(urlRepository.findByShortCodeAndIsActiveTrue("abcdef"))
                .thenReturn(Optional.of(url));

        urlService.deactivate("abcdef");

        assertThat(url.getIsActive()).isFalse();
        verify(urlRepository).save(url);
        verify(cacheService).evictUrl("abcdef");
    }
}
