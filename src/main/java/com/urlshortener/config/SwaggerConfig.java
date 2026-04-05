package com.urlshortener.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class SwaggerConfig {

    @Value("${app.base-url}")
    private String baseUrl;

    @Bean
    public OpenAPI openAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("URL Shortener API")
                        .description("Distributed URL shortener with Redis caching, " +
                                     "token bucket rate limiting, and click analytics.")
                        .version("1.0.0")
                        .contact(new Contact()
                                .name("Meet Sinojia")
                                .url("https://github.com/MeetSinojia")))
                .servers(List.of(
                        new Server().url(baseUrl).description("Local server")
                ));
    }
}
