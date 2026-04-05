package com.urlshortener.service;

import org.springframework.stereotype.Service;

/**
 * Encodes a numeric ID into a Base62 short code using characters [a-z A-Z 0-9].
 *
 * Why Base62?
 * - URL-safe: no special characters that need percent-encoding
 * - Compact: 7 characters covers 62^7 = 3.5 trillion unique codes
 * - Human-readable: no ambiguous characters like 0/O or 1/l (unlike Base64)
 *
 * Example: ID 1000000 → "4c92" (4 chars), ID 56800235584 → "aaaaaaa" (7 chars)
 */
@Service
public class Base62Service {

    private static final String ALPHABET = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    private static final int    BASE     = ALPHABET.length(); // 62
    private static final int    MIN_LENGTH = 6;

    /**
     * Encodes a positive long ID to a Base62 string.
     * Pads with leading 'a' (which encodes to 0) to enforce minimum length.
     */
    public String encode(long id) {
        if (id <= 0) {
            throw new IllegalArgumentException("ID must be a positive number, got: " + id);
        }

        StringBuilder sb = new StringBuilder();
        long num = id;

        while (num > 0) {
            sb.append(ALPHABET.charAt((int) (num % BASE)));
            num /= BASE;
        }

        // Pad to minimum length for consistent short code appearance
        while (sb.length() < MIN_LENGTH) {
            sb.append(ALPHABET.charAt(0));
        }

        return sb.reverse().toString();
    }

    /**
     * Decodes a Base62 string back to its original numeric ID.
     */
    public long decode(String shortCode) {
        long result = 0;
        for (char c : shortCode.toCharArray()) {
            result = result * BASE + ALPHABET.indexOf(c);
        }
        return result;
    }
}
