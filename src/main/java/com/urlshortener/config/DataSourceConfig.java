package com.urlshortener.config;

import com.urlshortener.sharding.ShardRoutingDataSource;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import lombok.extern.slf4j.Slf4j;
import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.core.type.AnnotatedTypeMetadata;
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

@Slf4j
@Configuration
@EnableTransactionManagement
public class DataSourceConfig {

    @Value("${app.db.shard0.primary-url}")
    private String shard0PrimaryUrl;

    @Value("${app.db.shard0.replica-url}")
    private String shard0ReplicaUrl;

    @Value("${app.db.shard0.username}")
    private String shard0Username;

    @Value("${app.db.shard0.password}")
    private String shard0Password;

    @Value("${app.db.shard1.primary-url:}")
    private String shard1PrimaryUrl;

    @Value("${app.db.shard1.replica-url:}")
    private String shard1ReplicaUrl;

    @Value("${app.db.shard1.username:}")
    private String shard1Username;

    @Value("${app.db.shard1.password:}")
    private String shard1Password;

    @Value("${app.db.pool.max-size:10}")
    private int poolMaxSize;

    @Value("${app.db.pool.min-idle:2}")
    private int poolMinIdle;

    @Value("${app.db.shard-count:1}")
    private int shardCount;

    // ── Shard 0 DataSources ──────────────────────────────────────────────────

    @Bean("shard0Primary")
    public DataSource shard0Primary() {
        return buildHikari("shard0-primary", shard0PrimaryUrl, shard0Username, shard0Password);
    }

    @Bean("shard0Replica")
    public DataSource shard0Replica() {
        return buildHikari("shard0-replica", shard0ReplicaUrl, shard0Username, shard0Password);
    }

    // ── Shard 1 DataSources (conditional) ───────────────────────────────────

    @Bean("shard1Primary")
    @Conditional(Shard1Condition.class)
    public DataSource shard1Primary() {
        return buildHikari("shard1-primary", shard1PrimaryUrl, shard1Username, shard1Password);
    }

    @Bean("shard1Replica")
    @Conditional(Shard1Condition.class)
    public DataSource shard1Replica() {
        return buildHikari("shard1-replica", shard1ReplicaUrl, shard1Username, shard1Password);
    }

    // ── Flyway — runs migrations on shard0 primary (and shard1 if enabled) ──
    //
    // FIX 1: was calling .load() without .migrate() — table was never created.
    // Now calls .migrate() explicitly. Bean name "flyway" is used by
    // @DependsOn on entityManagerFactory() to guarantee ordering.

    @Bean("flyway")
    public Flyway flyway() {
        Flyway flyway = Flyway.configure()
                .dataSource(shard0Primary())
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                .load();
        flyway.migrate();
        log.info("Flyway migration complete on shard0-primary");

        if (shardCount > 1 && shard1PrimaryUrl != null && !shard1PrimaryUrl.isBlank()) {
            Flyway.configure()
                    .dataSource(shard1Primary())
                    .locations("classpath:db/migration")
                    .baselineOnMigrate(true)
                    .load()
                    .migrate();
            log.info("Flyway migration complete on shard1-primary");
        }

        return flyway;
    }

    // ── Routing DataSource ───────────────────────────────────────────────────

    @Primary
    @Bean("routingDataSource")
    @DependsOn("flyway")   // ensure Flyway runs before routing DS is built
    public DataSource routingDataSource() {
        Map<Object, Object> targetDataSources = new HashMap<>();
        targetDataSources.put("shard0-primary", shard0Primary());
        targetDataSources.put("shard0-replica", shard0Replica());

        if (shardCount > 1) {
            targetDataSources.put("shard1-primary", shard1Primary());
            targetDataSources.put("shard1-replica", shard1Replica());
        }

        ShardRoutingDataSource router = new ShardRoutingDataSource(shardCount);
        router.setTargetDataSources(targetDataSources);
        router.setDefaultTargetDataSource(shard0Primary());
        router.afterPropertiesSet();

        log.info("RoutingDataSource initialized with {} shard(s)", shardCount);
        return new LazyConnectionDataSourceProxy(router);
    }

    // ── JPA EntityManagerFactory ─────────────────────────────────────────────
    //
    // FIX 2: @DependsOn("flyway") guarantees Hibernate schema validation
    // runs AFTER Flyway has created all tables. Without this, Spring may
    // initialize the EntityManagerFactory before migrations complete,
    // causing "Schema-validation: missing table [urls]".

    @Bean
    @DependsOn("flyway")
    public LocalContainerEntityManagerFactoryBean entityManagerFactory() {
        LocalContainerEntityManagerFactoryBean emf = new LocalContainerEntityManagerFactoryBean();
        emf.setDataSource(routingDataSource());
        emf.setPackagesToScan("com.urlshortener.model");

        HibernateJpaVendorAdapter adapter = new HibernateJpaVendorAdapter();
        emf.setJpaVendorAdapter(adapter);

        Properties props = new Properties();
        props.setProperty("hibernate.dialect", "org.hibernate.dialect.PostgreSQLDialect");
        props.setProperty("hibernate.hbm2ddl.auto", "validate");
        emf.setJpaProperties(props);

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

    private HikariDataSource buildHikari(String name, String url, String user, String pass) {
        HikariConfig config = new HikariConfig();
        config.setPoolName(name);
        config.setJdbcUrl(url);
        config.setUsername(user);
        config.setPassword(pass);
        config.setDriverClassName("org.postgresql.Driver");
        config.setMaximumPoolSize(poolMaxSize);
        config.setMinimumIdle(poolMinIdle);
        log.info("Configured pool {} → {}", name, url);
        return new HikariDataSource(config);
    }

    // ── Shard1Condition ───────────────────────────────────────────────────────

    public static class Shard1Condition implements Condition {
        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            String value = context.getEnvironment().getProperty("app.db.shard-count", "1");
            return Integer.parseInt(value) > 1;
        }
    }
}