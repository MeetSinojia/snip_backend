package com.urlshortener.sharding;

import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * ShardRoutingDataSource — routes each DB connection to the correct
 * shard primary (write) or shard replica (read) DataSource.
 *
 * Lookup key format:  "shard{N}-primary"  or  "shard{N}-replica"
 * Examples           :  "shard0-primary"  |  "shard1-replica"
 *
 * Routing decision:
 *   1. Shard index   → from ShardContext ThreadLocal (set by service layer
 *                        BEFORE the transaction opens)
 *   2. Read vs Write → from Spring's TransactionSynchronizationManager
 *                        (set automatically by @Transactional(readOnly = true))
 *
 * DataSource map (populated in DataSourceConfig):
 * ┌────────────────────┬──────────────────────────────────┐
 * │ Key                │ Target                           │
 * ├────────────────────┼──────────────────────────────────┤
 * │ "shard0-primary"   │ Shard-0 PostgreSQL primary       │
 * │ "shard0-replica"   │ Shard-0 PostgreSQL read replica  │
 * │ "shard1-primary"   │ Shard-1 PostgreSQL primary       │
 * │ "shard1-replica"   │ Shard-1 PostgreSQL read replica  │
 * └────────────────────┴──────────────────────────────────┘
 *
 * Fallback: if no shard context is set (e.g., background tasks, Flyway),
 * defaults to "shard0-primary".
 */
@Slf4j
public class ShardRoutingDataSource extends AbstractRoutingDataSource {

    private final int shardCount;

    public ShardRoutingDataSource(int shardCount) {
        this.shardCount = shardCount;
    }

    @Override
    protected Object determineCurrentLookupKey() {
        Integer shardIndex = ShardContext.get();

        // Default to shard 0 when no context is set (startup, Flyway, health checks)
        if (shardIndex == null) {
            shardIndex = 0;
        }

        // Clamp to valid shard range (defensive)
        shardIndex = Math.abs(shardIndex) % shardCount;

        boolean isReadOnly = TransactionSynchronizationManager.isCurrentTransactionReadOnly();
        String role = isReadOnly ? "replica" : "primary";
        String key = "shard" + shardIndex + "-" + role;

        log.info("ROUTING → key={} shard={} readOnly={}", key, shardIndex, isReadOnly);
        return key;
    }
}