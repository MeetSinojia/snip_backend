package com.urlshortener.middleware;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.urlshortener.cache.RedisCacheService;
import com.urlshortener.model.ErrorResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * RateLimitFilter — UPDATED
 *
 * Excluded paths (no rate limiting):
 *   /actuator, /swagger-ui, /api-docs           — infra
 *   /api/debug/rate-status                       — console reads
 *   /api/debug/reset-rate-limit                  — reset action
 *   /api/debug/config                            — NEW: config fetch (never rate-limited)
 *
 * NOT excluded — goes through real rate limiter:
 *   /api/debug/simulate-request                  — simulator needs real 429s
 *
 * Replace at: src/main/java/com/urlshortener/middleware/RateLimitFilter.java
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RateLimitFilter extends OncePerRequestFilter {

    private final RedisCacheService cacheService;
    private final ObjectMapper      objectMapper;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        String path = request.getRequestURI();

        if (isExcluded(path)) {
            filterChain.doFilter(request, response);
            return;
        }

        String ip = resolveClientIp(request);

        if (!cacheService.isAllowed(ip)) {
            log.warn("Rate limit exceeded for IP={} path={}", ip, path);
            writeRateLimitResponse(response, ip);
            return;
        }

        filterChain.doFilter(request, response);
    }

    private String resolveClientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    private boolean isExcluded(String path) {
        return path.startsWith("/actuator")
                || path.startsWith("/swagger-ui")
                || path.startsWith("/api-docs")
                || path.equals("/api/debug/rate-status")
                || path.equals("/api/debug/reset-rate-limit")
                || path.equals("/api/debug/config");        // ← NEW
    }

    private void writeRateLimitResponse(HttpServletResponse response, String ip)
            throws IOException {
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setHeader("X-RateLimit-Reset", "60");
        response.setHeader("X-RateLimit-IP", ip);

        ErrorResponse error = ErrorResponse.of(429, "Rate limit exceeded. Please try again later.");
        response.getWriter().write(objectMapper.writeValueAsString(error));
    }
}