package com.urlshortener.exception;

public class RateLimitException extends RuntimeException {

    public RateLimitException(String ip) {
        super("Rate limit exceeded for IP: " + ip + ". Please try again later.");
    }
}
