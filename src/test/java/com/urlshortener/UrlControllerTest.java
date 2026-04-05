package com.urlshortener;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.urlshortener.controller.UrlController;
import com.urlshortener.exception.GlobalExceptionHandler;
import com.urlshortener.exception.UrlNotFoundException;
import com.urlshortener.model.UrlRequest;
import com.urlshortener.model.UrlResponse;
import com.urlshortener.service.UrlService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(UrlController.class)
@Import(GlobalExceptionHandler.class)
class UrlControllerTest {

    @Autowired private MockMvc     mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @MockBean private UrlService urlService;

    // ── POST /api/shorten ─────────────────────────────────────────────────────

    @Test
    @DisplayName("POST /api/shorten returns 201 with valid URL")
    void shorten_validRequest_returns201() throws Exception {
        UrlRequest  request  = new UrlRequest("https://example.com/long/path", null);
        UrlResponse response = UrlResponse.builder()
                .shortCode("abcdef")
                .shortUrl("http://localhost:8080/abcdef")
                .originalUrl("https://example.com/long/path")
                .createdAt(LocalDateTime.now())
                .build();

        when(urlService.shorten(any(UrlRequest.class))).thenReturn(response);

        mockMvc.perform(post("/api/shorten")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.shortCode").value("abcdef"))
                .andExpect(jsonPath("$.shortUrl").value("http://localhost:8080/abcdef"));
    }

    @Test
    @DisplayName("POST /api/shorten returns 400 for blank URL")
    void shorten_blankUrl_returns400() throws Exception {
        UrlRequest request = new UrlRequest("", null);

        mockMvc.perform(post("/api/shorten")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    @DisplayName("POST /api/shorten returns 400 for URL without http/https")
    void shorten_invalidUrlScheme_returns400() throws Exception {
        UrlRequest request = new UrlRequest("ftp://example.com", null);

        mockMvc.perform(post("/api/shorten")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    // ── GET /{shortCode} ──────────────────────────────────────────────────────

    @Test
    @DisplayName("GET /{shortCode} returns 302 redirect for valid code")
    void redirect_validCode_returns302() throws Exception {
        when(urlService.resolve("abcdef")).thenReturn("https://example.com");

        mockMvc.perform(get("/abcdef"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "https://example.com"));
    }

    @Test
    @DisplayName("GET /{shortCode} returns 404 for unknown code")
    void redirect_unknownCode_returns404() throws Exception {
        when(urlService.resolve("unknown")).thenThrow(new UrlNotFoundException("unknown"));

        mockMvc.perform(get("/unknown"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    // ── DELETE /api/urls/{shortCode} ──────────────────────────────────────────

    @Test
    @DisplayName("DELETE /api/urls/{shortCode} returns 204 on success")
    void deactivate_validCode_returns204() throws Exception {
        mockMvc.perform(delete("/api/urls/abcdef"))
                .andExpect(status().isNoContent());
    }
}
