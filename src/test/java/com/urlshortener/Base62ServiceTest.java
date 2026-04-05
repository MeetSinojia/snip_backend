package com.urlshortener;

import com.urlshortener.service.Base62Service;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class Base62ServiceTest {

    private Base62Service base62Service;

    @BeforeEach
    void setUp() {
        base62Service = new Base62Service();
    }

    @Test
    @DisplayName("Encode produces minimum 6 character code")
    void encode_producesMinimumLength() {
        String code = base62Service.encode(1L);
        assertThat(code).hasSizeGreaterThanOrEqualTo(6);
    }

    @Test
    @DisplayName("Encode and decode are inverse operations")
    void encode_decode_areInverse() {
        long originalId = 123456789L;
        String encoded  = base62Service.encode(originalId);
        long decoded    = base62Service.decode(encoded);
        assertThat(decoded).isEqualTo(originalId);
    }

    @Test
    @DisplayName("Different IDs produce different short codes")
    void encode_differentIds_differentCodes() {
        String code1 = base62Service.encode(1L);
        String code2 = base62Service.encode(2L);
        assertThat(code1).isNotEqualTo(code2);
    }

    @Test
    @DisplayName("Encoded code contains only Base62 characters")
    void encode_containsOnlyValidCharacters() {
        String code = base62Service.encode(999999L);
        assertThat(code).matches("[a-zA-Z0-9]+");
    }

    @Test
    @DisplayName("Encoding zero or negative ID throws exception")
    void encode_invalidId_throwsException() {
        assertThatThrownBy(() -> base62Service.encode(0L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> base62Service.encode(-1L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Large IDs encode and decode correctly")
    void encode_largeId_roundTrip() {
        long largeId = 9_999_999_999L;
        assertThat(base62Service.decode(base62Service.encode(largeId))).isEqualTo(largeId);
    }
}
