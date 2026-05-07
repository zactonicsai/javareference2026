package com.example.demo.config;

import jakarta.persistence.EntityManagerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.orm.jpa.EntityManagerFactoryBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;

/**
 * H2 DataSource — primary, used by the existing H2-backed CRUD and by file metadata.
 *
 * Repositories under com.example.demo.repository.h2 are bound to this EntityManagerFactory.
 *
 * NOTE: @EnableJpaAuditing is intentionally NOT declared here. It lives on a
 * dedicated, otherwise-empty @Configuration class (JpaAuditingConfig). Putting
 * @EnableJpaAuditing on the same class as @EnableJpaRepositories in a multi-
 * datasource setup can cause the auditing registrar to register the
 * 'jpaAuditingHandler' bean twice in Spring Data 3.5.x, which fails with
 * BeanDefinitionOverrideException because Spring Boot disables override by default.
 */
@Configuration
@EnableJpaRepositories(
        basePackages = "com.example.demo.repository.h2",
        entityManagerFactoryRef = "h2EntityManagerFactory",
        transactionManagerRef  = "h2TransactionManager"
)
public class H2DataSourceConfig {

    @Primary
    @Bean
    @ConfigurationProperties("spring.datasource.h2")
    public DataSourceProperties h2DataSourceProperties() {
        return new DataSourceProperties();
    }

    @Primary
    @Bean(name = "h2DataSource")
    @ConfigurationProperties("spring.datasource.h2.hikari")
    public DataSource h2DataSource(@Qualifier("h2DataSourceProperties") DataSourceProperties props) {
        return props.initializeDataSourceBuilder().build();
    }

    @Primary
    @Bean(name = "h2EntityManagerFactory")
    public LocalContainerEntityManagerFactoryBean h2EntityManagerFactory(
            EntityManagerFactoryBuilder builder,
            @Qualifier("h2DataSource") DataSource ds) {
        return builder
                .dataSource(ds)
                .packages("com.example.demo.entity.h2")
                .persistenceUnit("h2")
                .build();
    }

    @Primary
    @Bean(name = "h2TransactionManager")
    public PlatformTransactionManager h2TransactionManager(
            @Qualifier("h2EntityManagerFactory") EntityManagerFactory emf) {
        return new JpaTransactionManager(emf);
    }
}
