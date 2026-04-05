package com.urlshortener.controller;

import com.urlshortener.model.UrlRequest;
import com.urlshortener.model.UrlResponse;
import com.urlshortener.service.UrlService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequiredArgsConstructor
@Tag(name = "URL Shortener", description = "Shorten, resolve and manage URLs")
public class UrlController {

    private final UrlService urlService;

    // ── POST /api/shorten ─────────────────────────────────────────────────────

    @Operation(summary = "Shorten a URL", description = "Accepts a long URL and returns a short code")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "URL shortened successfully"),
        @ApiResponse(responseCode = "400", description = "Invalid URL format"),
        @ApiResponse(responseCode = "429", description = "Rate limit exceeded")
    })
    @PostMapping("/api/shorten")
    public ResponseEntity<UrlResponse> shorten(@Valid @RequestBody UrlRequest request) {
        log.info("Shorten request for url={}", request.getOriginalUrl());
        UrlResponse response = urlService.shorten(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    // ── GET /{shortCode} ──────────────────────────────────────────────────────

    @Operation(summary = "Redirect to original URL", description = "Resolves a short code and redirects with HTTP 302")
    @ApiResponses({
        @ApiResponse(responseCode = "302", description = "Redirect to original URL"),
        @ApiResponse(responseCode = "404", description = "Short code not found or expired"),
        @ApiResponse(responseCode = "429", description = "Rate limit exceeded")
    })
    @GetMapping("/{shortCode}")
    public ResponseEntity<Void> redirect(@PathVariable String shortCode) {
        log.info("Redirect request for shortCode={}", shortCode);
        String originalUrl = urlService.resolve(shortCode);

        return ResponseEntity.status(HttpStatus.FOUND)
                .header(HttpHeaders.LOCATION, originalUrl)
                .build();
    }

    // ── DELETE /api/urls/{shortCode} ──────────────────────────────────────────

    @Operation(summary = "Deactivate a short URL", description = "Soft-deletes a URL and evicts it from cache")
    @ApiResponses({
        @ApiResponse(responseCode = "204", description = "URL deactivated"),
        @ApiResponse(responseCode = "404", description = "Short code not found")
    })
    @DeleteMapping("/api/urls/{shortCode}")
    public ResponseEntity<Void> deactivate(@PathVariable String shortCode) {
        log.info("Deactivate request for shortCode={}", shortCode);
        urlService.deactivate(shortCode);
        return ResponseEntity.noContent().build();
    }
}
