package com.urlshortener.sharding;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import com.google.common.hash.Hashing;
import java.nio.charset.StandardCharsets;
/**
 * ShardingService — computes the target shard for a given shortCode.
 *
 * Strategy: consistent hash of shortCode modulo total shard count.
 *
 * Why hash(shortCode) and not hash(id)?
 * ───────────────────────────────────────
 * All read AND write operations use the shortCode as their primary lookup key.
 * Hashing the shortCode ensures both INSERT and SELECT land on the same shard
 * without any cross-shard joins. ID-based hashing would work too since shortCode
 * is derived from the Snowflake ID, but shortCode is more natural as the shard key
 * because it's the query predicate for every DB operation.
 *
 * Distribution: Java's String.hashCode() distributes Base62 strings well
 * enough for typical workloads. For production at large scale, replace with
 * MurmurHash3 via Guava's Hashing.murmur3_32() for better distribution guarantees.
 *
 * Example with 2 shards:
 *   "abc123" → hashCode = 2090745547 → shard 1
 *   "xyz789" → hashCode = 1234567890 → shard 0
 */
@Slf4j
@Service
public class ShardingService {

    @Value("${app.db.shard-count:2}")
    private int shardCount;

    /**
     * Returns the shard index (0-based) for the given shortCode.
     * Guaranteed to return a value in [0, shardCount).
     */
    public int getShardIndex(String shortCode) {
    int hash = Hashing.murmur3_32_fixed()
            .hashString(shortCode, StandardCharsets.UTF_8)
            .asInt() & Integer.MAX_VALUE;

    int shard = hash % shardCount;

    log.info("SHARD_DECISION → shortCode={} shard={}", shortCode, shard);
    return shard;
}

    /**
     * Returns the total shard count.
     * Used by DataSourceConfig and tests.
     */
    public int getShardCount() {
        return shardCount;
    }
}