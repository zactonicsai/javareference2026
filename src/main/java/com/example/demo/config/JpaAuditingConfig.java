package com.example.demo.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

/**
 * Dedicated, single-purpose host for @EnableJpaAuditing.
 *
 * Why it lives in its OWN class — and not on H2DataSourceConfig:
 *  - In a multi-datasource setup with two @EnableJpaRepositories declarations
 *    (one per datasource), Spring Data 3.5.x can invoke the auditing registrar
 *    more than once if @EnableJpaAuditing rides on a class that also declares
 *    @EnableJpaRepositories. That triggers a BeanDefinitionOverrideException
 *    on 'jpaAuditingHandler' because Spring Boot disables override by default.
 *  - Keeping this annotation on a class that does literally nothing else makes
 *    the registrar fire exactly once.
 *
 * Why it is excluded from the worker profile:
 *  - The worker does not write anything that uses Spring Data's
 *    @CreatedDate / @LastModifiedDate. ProductPg keeps its timestamps via
 *    explicit @PrePersist / @PreUpdate callbacks. Loading the auditing
 *    infrastructure in the worker would just register beans nothing uses.
 */
@Configuration
@Profile("!worker")
@EnableJpaAuditing
public class JpaAuditingConfig {
}
