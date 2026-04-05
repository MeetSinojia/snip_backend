package com.urlshortener.exception;

public class UrlNotFoundException extends RuntimeException {

    public UrlNotFoundException(String shortCode) {
        super("No active URL found for short code: " + shortCode);
    }
}
