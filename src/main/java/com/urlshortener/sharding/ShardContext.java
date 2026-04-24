package com.urlshortener.sharding;

/**
 * ShardContext — ThreadLocal holder for the current shard index.
 *
 * This must be set BEFORE a @Transactional method is entered, because
 * Spring opens the DB connection (and therefore calls
 * AbstractRoutingDataSource.determineCurrentLookupKey()) at transaction start.
 *
 * Usage pattern (see UrlServiceImpl):
 * <pre>
 *   ShardContext.set(shardingService.getShardIndex(shortCode));
 *   try {
 *       transactionTemplate.execute(...); // connection routed to correct shard
 *   } finally {
 *       ShardContext.clear(); // always clean up to prevent thread-pool leaks
 *   }
 * </pre>
 *
 * Read vs. Write routing is resolved separately inside ShardRoutingDataSource
 * by inspecting TransactionSynchronizationManager.isCurrentTransactionReadOnly().
 */
public final class ShardContext {

    private static final ThreadLocal<Integer> SHARD_INDEX = new ThreadLocal<>();

    private ShardContext() {}

    public static void set(int shardIndex) {
        SHARD_INDEX.set(shardIndex);
    }

    public static Integer get() {
        return SHARD_INDEX.get();
    }

    public static void clear() {
        SHARD_INDEX.remove();
    }
}