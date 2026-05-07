package com.example.demo.config;

import com.example.demo.entity.h2.Product;
import com.example.demo.repository.h2.ProductRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

/**
 * Seeds the H2 products table on first boot. Skipped in the worker profile
 * since the worker doesn't need H2 fixtures.
 *
 * Postgres seeding is intentionally not done here — exercising the new
 * Temporal-backed POST /api/products-temporal endpoints is the point.
 */
@Slf4j
@Component
@Profile("!worker")
@RequiredArgsConstructor
public class DataSeeder implements ApplicationRunner {

    private final ProductRepository repository;

    @Override
    public void run(ApplicationArguments args) {
        if (repository.count() > 0) {
            log.info("H2 products table already populated, skipping seed");
            return;
        }
        repository.saveAll(List.of(
                Product.builder().name("Wireless Mouse")
                        .description("Ergonomic 2.4GHz wireless mouse")
                        .price(new BigDecimal("29.99")).stock(120).build(),
                Product.builder().name("Mechanical Keyboard")
                        .description("Cherry MX Brown switches, RGB backlight")
                        .price(new BigDecimal("119.50")).stock(45).build(),
                Product.builder().name("USB-C Hub")
                        .description("7-in-1 hub with HDMI, SD, USB-A x3")
                        .price(new BigDecimal("49.00")).stock(80).build(),
                Product.builder().name("Noise-Cancelling Headphones")
                        .description("Over-ear with active noise cancellation")
                        .price(new BigDecimal("299.00")).stock(25).build()
        ));
        log.info("Seeded {} H2 products", repository.count());
    }
}
