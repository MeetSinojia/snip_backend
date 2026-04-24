package com.urlshortener.service;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * SnowflakeIdGenerator — distributed, time-sortable 64-bit ID generation.
 *
 * Bit layout (total 63 usable bits, sign bit always 0):
 * ┌───────────────────────────────────────────────────────────────┐
 * │  0 │         41 bits timestamp        │ 10 bits node │ 12 seq │
 * └───────────────────────────────────────────────────────────────┘
 *   [63]         [62 ── 22]                 [21 ── 12]    [11 ── 0]
 *
 * Timestamp : milliseconds since EPOCH (2024-01-01T00:00:00Z)
 *             → 41 bits gives ~69 years of unique IDs
 * Node ID   : 10 bits → supports 1024 application nodes (0-1023)
 *             Use datacenter_id (5 bits) + worker_id (5 bits) in prod
 * Sequence  : 12 bits → 4096 unique IDs per millisecond per node
 *
 * Why this replaces DB auto-increment:
 * ─────────────────────────────────────
 * With BIGSERIAL, the ID is only known after the first INSERT, which
 * forced a two-step save (save "pending" → update shortCode). Snowflake
 * generates the ID in-process before any DB call, allowing a single INSERT
 * and enabling shard routing based on shortCode before the transaction opens.
 */
@Slf4j
@Service
public class SnowflakeIdGenerator {

    // Custom epoch: 2024-01-01T00:00:00Z in milliseconds
    private static final long CUSTOM_EPOCH = 1704067200000L;

    // Bit lengths
    private static final long NODE_ID_BITS  = 10L;
    private static final long SEQUENCE_BITS = 12L;

    // Max values
    private static final long MAX_NODE_ID   = (1L << NODE_ID_BITS) - 1;   // 1023
    private static final long MAX_SEQUENCE  = (1L << SEQUENCE_BITS) - 1;  // 4095

    // Bit shifts
    private static final long NODE_ID_SHIFT       = SEQUENCE_BITS;                  // 12
    private static final long TIMESTAMP_LEFT_SHIFT = NODE_ID_BITS + SEQUENCE_BITS; // 22

    @Value("${app.snowflake.node-id:0}")
    private long nodeId;

    private long sequence         = 0L;
    private long lastTimestamp    = -1L;

    @PostConstruct
    public void validate() {
        if (nodeId < 0 || nodeId > MAX_NODE_ID) {
            throw new IllegalStateException(
                "Snowflake node-id must be between 0 and " + MAX_NODE_ID + ", got: " + nodeId
            );
        }
        log.info("SnowflakeIdGenerator initialized with nodeId={}", nodeId);
    }

    /**
     * Generates the next globally unique Snowflake ID.
     * Thread-safe via synchronization on the instance.
     *
     * @return a positive 64-bit long that encodes timestamp + nodeId + sequence
     */
    public synchronized long nextId() {
        long currentTimestamp = currentTimeMs();

        if (currentTimestamp < lastTimestamp) {
            // Clock moved backward — wait until we catch up (max tolerable drift: 2ms)
            long drift = lastTimestamp - currentTimestamp;
            if (drift <= 2) {
                currentTimestamp = lastTimestamp;
            } else {
                throw new IllegalStateException(
                    "Clock moved backward by " + drift + "ms. Refusing to generate ID."
                );
            }
        }

        if (currentTimestamp == lastTimestamp) {
            // Same millisecond — increment sequence
            sequence = (sequence + 1) & MAX_SEQUENCE;
            if (sequence == 0) {
                // Sequence exhausted for this ms — spin until next ms
                currentTimestamp = waitForNextMillis(lastTimestamp);
            }
        } else {
            // New millisecond — reset sequence
            sequence = 0L;
        }

        lastTimestamp = currentTimestamp;

        long id = ((currentTimestamp - CUSTOM_EPOCH) << TIMESTAMP_LEFT_SHIFT)
                | (nodeId << NODE_ID_SHIFT)
                | sequence;

        log.trace("Generated snowflake id={} ts={} node={} seq={}", id, currentTimestamp, nodeId, sequence);
        return id;
    }

    /**
     * Extracts the creation timestamp from a Snowflake ID.
     * Useful for debugging or TTL calculations.
     */
    public long extractTimestamp(long snowflakeId) {
        return (snowflakeId >> TIMESTAMP_LEFT_SHIFT) + CUSTOM_EPOCH;
    }

    private long currentTimeMs() {
        return System.currentTimeMillis();
    }

    private long waitForNextMillis(long lastTs) {
        long ts = currentTimeMs();
        while (ts <= lastTs) {
            ts = currentTimeMs();
        }
        return ts;
    }
}