package com.example.demo.config;

import jakarta.persistence.EntityManagerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.orm.jpa.EntityManagerFactoryBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.util.HashMap;
import java.util.Map;

/**
 * Postgres DataSource — secondary, used by Temporal-orchestrated CRUD.
 *
 * Repositories under com.example.demo.repository.pg are bound to this EntityManagerFactory.
 */
@Configuration
@EnableJpaRepositories(
        basePackages = "com.example.demo.repository.pg",
        entityManagerFactoryRef = "pgEntityManagerFactory",
        transactionManagerRef  = "pgTransactionManager"
)
public class PgDataSourceConfig {

    @Bean
    @ConfigurationProperties("spring.datasource.postgres")
    public DataSourceProperties pgDataSourceProperties() {
        return new DataSourceProperties();
    }

    @Bean(name = "pgDataSource")
    @ConfigurationProperties("spring.datasource.postgres.hikari")
    public DataSource pgDataSource(@Qualifier("pgDataSourceProperties") DataSourceProperties props) {
        return props.initializeDataSourceBuilder().build();
    }

    @Bean(name = "pgEntityManagerFactory")
    public LocalContainerEntityManagerFactoryBean pgEntityManagerFactory(
            EntityManagerFactoryBuilder builder,
            @Qualifier("pgDataSource") DataSource ds,
            org.springframework.core.env.Environment env) {
        Map<String, Object> jpaProps = new HashMap<>();
        // Hibernate auto-detects the dialect from the JDBC connection metadata.
        // The 'app.pg.hibernate.ddl-auto' property lets tests pick a different mode.
        jpaProps.put("hibernate.hbm2ddl.auto",
                env.getProperty("app.pg.hibernate.ddl-auto", "update"));
        return builder
                .dataSource(ds)
                .packages("com.example.demo.entity.pg")
                .persistenceUnit("pg")
                .properties(jpaProps)
                .build();
    }

    @Bean(name = "pgTransactionManager")
    public PlatformTransactionManager pgTransactionManager(
            @Qualifier("pgEntityManagerFactory") EntityManagerFactory emf) {
        return new JpaTransactionManager(emf);
    }
}
