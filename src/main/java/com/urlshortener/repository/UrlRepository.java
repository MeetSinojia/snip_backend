package com.urlshortener.repository;

import com.urlshortener.model.Url;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface UrlRepository extends JpaRepository<Url, Long> {

    Optional<Url> findByShortCodeAndIsActiveTrue(String shortCode);

    boolean existsByShortCode(String shortCode);

    // Fetch all expired URLs for cleanup job
    @Query("SELECT u FROM Url u WHERE u.expiresAt IS NOT NULL AND u.expiresAt < :now AND u.isActive = true")
    List<Url> findAllExpired(LocalDateTime now);

    // Soft-delete expired URLs in bulk
    @Modifying
    @Query("UPDATE Url u SET u.isActive = false WHERE u.expiresAt IS NOT NULL AND u.expiresAt < :now")
    int deactivateExpiredUrls(LocalDateTime now);
}
