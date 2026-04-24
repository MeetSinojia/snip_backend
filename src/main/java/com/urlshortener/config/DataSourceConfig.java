package com.urlshortener.config;

import com.urlshortener.sharding.ShardRoutingDataSource;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import lombok.extern.slf4j.Slf4j;
import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.datasource.LazyConnectionDataSourceProxy;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import javax.sql.DataSource;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

/**
 * DataSourceConfig — wires up the full sharded + read-replica DataSource topology.
 *
 * Infrastructure layout (2 shards, configurable via app.db.shard-count):
 *
 *   ┌──────────────────┐     ┌──────────────────┐
 *   │  Shard 0 Primary │     │  Shard 1 Primary │   ← writes
 *   │  (localhost:5432)│     │  (localhost:5434)│
 *   └────────┬─────────┘     └────────┬─────────┘
 *            │ replication            │ replication
 *   ┌────────▼─────────┐     ┌────────▼─────────┐
 *   │  Shard 0 Replica │     │  Shard 1 Replica │   ← reads
 *   │  (localhost:5433)│     │  (localhost:5435)│
 *   └──────────────────┘     └──────────────────┘
 *             ▲                        ▲
 *             └──────────┬─────────────┘
 *               ShardRoutingDataSource
 *               (AbstractRoutingDataSource)
 *                         ▲
 *              LazyConnectionDataSourceProxy
 *                         ▲
 *              EntityManagerFactory / JPA
 *
 * LazyConnectionDataSourceProxy is critical: it delays acquiring the physical
 * connection until the first actual SQL statement, giving Spring's
 * TransactionSynchronizationManager time to register the readOnly flag before
 * AbstractRoutingDataSource.determineCurrentLookupKey() is called.
 *
 * Flyway: runs migrations only on each shard's PRIMARY DataSource at startup.
 *
 * Important: Add @SpringBootApplication(exclude = DataSourceAutoConfiguration.class)
 * to UrlShortenerApplication — this config owns all DataSource beans.
 */
@Slf4j
@Configuration
@EnableTransactionManagement
public class DataSourceConfig {

    // ── Shard 0 config ──────────────────────────────────────────────────────

    @Value("${app.db.shard0.primary-url:jdbc:postgresql://localhost:5432/urlshortener_shard0}")
    private String shard0PrimaryUrl;

    @Value("${app.db.shard0.replica-url:jdbc:postgresql://localhost:5433/urlshortener_shard0}")
    private String shard0ReplicaUrl;

    @Value("${app.db.shard0.username:postgres}")
    private String shard0Username;

    @Value("${app.db.shard0.password:postgres}")
    private String shard0Password;

    // ── Shard 1 config ──────────────────────────────────────────────────────

    @Value("${app.db.shard1.primary-url:jdbc:postgresql://localhost:5434/urlshortener_shard1}")
    private String shard1PrimaryUrl;

    @Value("${app.db.shard1.replica-url:jdbc:postgresql://localhost:5435/urlshortener_shard1}")
    private String shard1ReplicaUrl;

    @Value("${app.db.shard1.username:postgres}")
    private String shard1Username;

    @Value("${app.db.shard1.password:postgres}")
    private String shard1Password;

    // ── Shared connection pool config ────────────────────────────────────────

    @Value("${app.db.pool.max-size:10}")
    private int poolMaxSize;

    @Value("${app.db.pool.min-idle:2}")
    private int poolMinIdle;

    @Value("${app.db.shard-count:2}")
    private int shardCount;

    // ── Individual DataSource beans ──────────────────────────────────────────

    @Bean("shard0Primary")
    public DataSource shard0Primary() {
        return buildHikariDataSource("shard0-primary", shard0PrimaryUrl, shard0Username, shard0Password);
    }

    @Bean("shard0Replica")
    public DataSource shard0Replica() {
        return buildHikariDataSource("shard0-replica", shard0ReplicaUrl, shard0Username, shard0Password);
    }

    @Bean("shard1Primary")
    public DataSource shard1Primary() {
        return buildHikariDataSource("shard1-primary", shard1PrimaryUrl, shard1Username, shard1Password);
    }

    @Bean("shard1Replica")
    public DataSource shard1Replica() {
        return buildHikariDataSource("shard1-replica", shard1ReplicaUrl, shard1Username, shard1Password);
    }

    // ── Flyway: run migrations on each primary ───────────────────────────────

    @Bean("flywayMigrationShard0")
    public Flyway flywayMigrationShard0() {
        return runFlyway(shard0Primary());
    }

    @Bean("flywayMigrationShard1")
    public Flyway flywayMigrationShard1() {
        return runFlyway(shard1Primary());
    }

    // ── Routing DataSource (the one JPA uses) ────────────────────────────────

    /**
     * ShardRoutingDataSource builds the lookup map and delegates to
     * the right physical DataSource per request.
     *
     * Wrapped in LazyConnectionDataSourceProxy so that
     * determineCurrentLookupKey() is called AFTER Spring registers
     * the transaction's readOnly flag — ensuring correct read/write routing.
     */
    @Primary
    @Bean("routingDataSource")
    @DependsOn({"flywayMigrationShard0", "flywayMigrationShard1"})
    public DataSource routingDataSource() {
        Map<Object, Object> targetDataSources = new HashMap<>();
        targetDataSources.put("shard0-primary", shard0Primary());
        targetDataSources.put("shard0-replica", shard0Replica());
        targetDataSources.put("shard1-primary", shard1Primary());
        targetDataSources.put("shard1-replica", shard1Replica());

        ShardRoutingDataSource router = new ShardRoutingDataSource(shardCount);
        router.setTargetDataSources(targetDataSources);
        router.setDefaultTargetDataSource(shard0Primary()); // fallback
        router.afterPropertiesSet();

        log.info("ShardRoutingDataSource configured with {} shards", shardCount);

        // LazyConnectionDataSourceProxy is essential for correct readOnly routing
        return new LazyConnectionDataSourceProxy(router);
    }

    // ── JPA EntityManagerFactory ─────────────────────────────────────────────

    @Bean
    public LocalContainerEntityManagerFactoryBean entityManagerFactory() {
        LocalContainerEntityManagerFactoryBean emf = new LocalContainerEntityManagerFactoryBean();
        emf.setDataSource(routingDataSource());
        emf.setPackagesToScan("com.urlshortener.model");

        HibernateJpaVendorAdapter vendorAdapter = new HibernateJpaVendorAdapter();
        vendorAdapter.setGenerateDdl(false); // Flyway owns DDL
        emf.setJpaVendorAdapter(vendorAdapter);

        Properties jpaProperties = new Properties();
        jpaProperties.setProperty("hibernate.dialect", "org.hibernate.dialect.PostgreSQLDialect");
        jpaProperties.setProperty("hibernate.show_sql", "false");
        jpaProperties.setProperty("hibernate.format_sql", "true");
        jpaProperties.setProperty("hibernate.hbm2ddl.auto", "validate");
        emf.setJpaProperties(jpaProperties);

        return emf;
    }

    // ── Transaction Manager ──────────────────────────────────────────────────

    @Bean
    public PlatformTransactionManager transactionManager() {
        JpaTransactionManager tm = new JpaTransactionManager();
        tm.setEntityManagerFactory(entityManagerFactory().getObject());
        return tm;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private HikariDataSource buildHikariDataSource(String poolName, String jdbcUrl,
                                                    String username, String password) {
        HikariConfig config = new HikariConfig();
        config.setPoolName(poolName);
        config.setJdbcUrl(jdbcUrl);
        config.setUsername(username);
        config.setPassword(password);
        config.setDriverClassName("org.postgresql.Driver");
        config.setMaximumPoolSize(poolMaxSize);
        config.setMinimumIdle(poolMinIdle);
        config.setConnectionTimeout(3000);           // 3s to get connection from pool
        config.setIdleTimeout(600_000);              // 10min idle before eviction
        config.setMaxLifetime(1_800_000);            // 30min max connection lifetime
        config.setConnectionTestQuery("SELECT 1");
        log.info("Configured HikariCP pool '{}' → {}", poolName, jdbcUrl);
        return new HikariDataSource(config);
    }

    private Flyway runFlyway(DataSource dataSource) {
        Flyway flyway = Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                .load();
        flyway.migrate();
        return flyway;
    }
}