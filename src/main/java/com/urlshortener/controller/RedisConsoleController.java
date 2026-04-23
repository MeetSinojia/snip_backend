package com.urlshortener.controller;

import com.urlshortener.cache.RedisCacheService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * RedisConsoleController — UPDATED
 *
 * Added: GET /api/debug/config  — returns rate-limit values from application.yml
 * so the frontend never has hardcoded numbers.
 *
 * Place at:
 *   src/main/java/com/urlshortener/controller/RedisConsoleController.java
 */
@RestController
@RequestMapping("/api/debug")
@RequiredArgsConstructor
@Tag(name = "Debug", description = "Redis console and rate-limit inspection endpoints")
public class RedisConsoleController {

    private final RedisCacheService cacheService;

    @Value("${app.rate-limit.capacity}")
    private long capacity;

    @Value("${app.rate-limit.refill-rate}")
    private long refillRate;

    @Value("${app.rate-limit.window-seconds}")
    private long windowSeconds;

    /**
     * GET /api/debug/config
     * Returns rate-limit config from application.yml.
     * Excluded from rate limiting in RateLimitFilter.
     */
    @Operation(summary = "Get rate-limit config from application.yml")
    @GetMapping("/config")
    public ResponseEntity<Map<String, Object>> getConfig() {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("capacity",      capacity);
        config.put("refillRate",    refillRate);
        config.put("windowSeconds", windowSeconds);
        config.put("source",        "application.yml → app.rate-limit");
        return ResponseEntity.ok(config);
    }

    @Operation(summary = "Get rate-limit status for caller IP")
    @GetMapping("/rate-status")
    public ResponseEntity<Map<String, Object>> getRateStatus(
            jakarta.servlet.http.HttpServletRequest request) {
        String ip = resolveIp(request);
        return ResponseEntity.ok(cacheService.getRateLimitStatus(ip));
    }

    @Operation(summary = "Simulate a shorten request for rate-limit demo")
    @PostMapping("/simulate-request")
    public ResponseEntity<Map<String, Object>> simulateRequest(
            jakarta.servlet.http.HttpServletRequest request) {
        String ip       = resolveIp(request);
        boolean allowed = cacheService.isAllowed(ip);
        Map<String, Object> status = cacheService.getRateLimitStatus(ip);
        status.put("allowed",    allowed);
        status.put("httpStatus", allowed ? 201 : 429);
        status.put("message",    allowed ? "Request accepted" : "Rate limit exceeded");
        return ResponseEntity.status(allowed ? 200 : 429).body(status);
    }

    @Operation(summary = "Reset rate-limit bucket for caller IP")
    @DeleteMapping("/reset-rate-limit")
    public ResponseEntity<Map<String, Object>> resetRateLimit(
            jakarta.servlet.http.HttpServletRequest request) {
        String ip = resolveIp(request);
        cacheService.resetRateLimit(ip);
        return ResponseEntity.ok(Map.of("ip", ip, "message", "Rate limit bucket reset"));
    }

    private String resolveIp(jakarta.servlet.http.HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) return forwarded.split(",")[0].trim();
        return request.getRemoteAddr();
    }
}