package com.example.demo.controller;

import com.example.demo.dto.ProductDto;
import com.example.demo.service.ProductTemporalService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;

/**
 * Same shape as ProductController but writes go through Temporal workflows
 * that persist to Postgres in their activities. Reads are direct Postgres queries.
 */
@RestController
@RequestMapping("/api/products-temporal")
@Profile("!worker")
@RequiredArgsConstructor
@Tag(name = "Products (Temporal/Postgres)",
        description = "CRUD where writes are durable Temporal workflows that save to Postgres")
public class ProductTemporalController {

    private final ProductTemporalService service;

    @GetMapping
    @Operation(summary = "List products from Postgres (direct read)")
    public List<ProductDto> list() {
        return service.findAll();
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get a product from Postgres (direct read)")
    public ProductDto get(@PathVariable Long id) {
        return service.findById(id);
    }

    @PostMapping
    @Operation(summary = "Create via CreateProductWorkflow (MANAGER+)")
    public ResponseEntity<ProductDto> create(@Valid @RequestBody ProductDto dto) {
        ProductDto created = service.create(dto);
        return ResponseEntity.created(URI.create("/api/products-temporal/" + created.getId())).body(created);
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update via UpdateProductWorkflow (MANAGER+)")
    public ProductDto update(@PathVariable Long id, @Valid @RequestBody ProductDto dto) {
        return service.update(id, dto);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete via DeleteProductWorkflow (ADMIN only)")
    public void delete(@PathVariable Long id) {
        service.delete(id);
    }
}
