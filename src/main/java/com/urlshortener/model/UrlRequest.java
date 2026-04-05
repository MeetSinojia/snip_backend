package com.urlshortener.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class UrlRequest {

    @NotBlank(message = "URL must not be blank")
    @Pattern(
        regexp = "^(https?://).+",
        message = "URL must start with http:// or https://"
    )
    @Size(max = 2048, message = "URL must not exceed 2048 characters")
    private String originalUrl;

    // Optional: custom expiry in hours (null = uses default TTL)
    private Integer expiryHours;
}
