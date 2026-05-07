package com.example.demo.config;

import com.example.demo.entity.h2.Product;
import com.example.demo.entity.pg.ProductPg;
import com.example.demo.repository.h2.ProductRepository;
import com.example.demo.repository.pg.ProductPgRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

/**
 * Seeds the H2 and Postgres products tables on first boot.
 *
 * Behavior:
 *  - Skipped in the worker profile — only the API process should seed.
 *  - Idempotent: each store is checked with count() before inserting, so the API
 *    can be restarted repeatedly without duplicating rows in Postgres (which
 *    persists across restarts via the docker volume). H2 is in-memory and is
 *    reseeded each boot.
 *  - Each repository handles its own transaction via its respective
 *    @EnableJpaRepositories(transactionManagerRef = ...). No @Transactional is
 *    required on the calling method.
 *  - The Postgres seed is wrapped in try/catch — if Postgres isn't reachable yet,
 *    we log and let the API come up so H2-backed endpoints still work.
 *
 * Why this is the seeder (and not data.sql):
 *    Spring Boot 3.x runs data.sql during DataSource initialization, which
 *    happens BEFORE Hibernate creates tables (defer-datasource-initialization
 *    defaults to false). That makes data.sql + ddl-auto:update a startup
 *    failure. ApplicationRunner runs after the full context is ready, so the
 *    schema is guaranteed to exist by the time we insert.
 */
@Slf4j
@Component
@Profile("!worker")
@RequiredArgsConstructor
public class DataSeeder implements ApplicationRunner {

    private final ProductRepository h2Repository;
    private final ProductPgRepository pgRepository;

    /** Single source of seed data, shared between both stores. */
    private record Seed(String name, String description, String price, int stock) { }

    private static final List<Seed> SEEDS = List.of(
            new Seed("Wireless Mouse",              "Ergonomic 2.4GHz wireless mouse",          "29.99",  120),
            new Seed("Mechanical Keyboard",         "Cherry MX Brown switches, RGB backlight",  "119.50", 45),
            new Seed("USB-C Hub",                   "7-in-1 hub with HDMI, SD, USB-A x3",       "49.00",  80),
            new Seed("Noise-Cancelling Headphones", "Over-ear with active noise cancellation",  "299.00", 25)
    );

    @Override
    public void run(ApplicationArguments args) {
        seedH2();
        seedPostgres();
    }

    private void seedH2() {
        long existing = h2Repository.count();
        if (existing > 0) {
            log.info("H2 products already populated ({} rows), skipping H2 seed", existing);
            return;
        }
        h2Repository.saveAll(SEEDS.stream()
                .map(s -> Product.builder()
                        .name(s.name())
                        .description(s.description())
                        .price(new BigDecimal(s.price()))
                        .stock(s.stock())
                        .build())
                .toList());
        log.info("Seeded {} H2 products", h2Repository.count());
    }

    private void seedPostgres() {
        try {
            long existing = pgRepository.count();
            if (existing > 0) {
                log.info("Postgres products already populated ({} rows), skipping Postgres seed", existing);
                return;
            }
            pgRepository.saveAll(SEEDS.stream()
                    .map(s -> ProductPg.builder()
                            .name(s.name())
                            .description(s.description())
                            .price(new BigDecimal(s.price()))
                            .stock(s.stock())
                            .build())
                    .toList());
            log.info("Seeded {} Postgres products", pgRepository.count());
        } catch (Exception e) {
            // Don't fail API startup on Postgres seed problems. Postgres may still
            // be coming up (compose healthcheck race) or the schema might be in
            // mid-update. The Temporal endpoints can be used to populate later.
            log.warn("Postgres seeding skipped due to error (API will continue starting): {}", e.getMessage());
        }
    }
}
