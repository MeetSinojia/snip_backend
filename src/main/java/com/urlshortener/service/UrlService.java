package com.urlshortener.service;

import com.urlshortener.model.UrlRequest;
import com.urlshortener.model.UrlResponse;

public interface UrlService {

    /**
     * Shortens a long URL and persists it.
     * Returns the generated short code and full short URL.
     */
    UrlResponse shorten(UrlRequest request);

    /**
     * Resolves a short code to its original URL.
     * Checks Redis cache first (cache-aside pattern), falls back to PostgreSQL.
     * Increments click counter on every successful resolution.
     */
    String resolve(String shortCode);

    /**
     * Soft-deletes a URL by marking it inactive and evicting from cache.
     */
    void deactivate(String shortCode);
}
