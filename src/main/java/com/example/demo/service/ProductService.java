package com.example.demo.service;

import com.example.demo.dto.ProductDto;
import com.example.demo.entity.h2.Product;
import com.example.demo.exception.ProductException;
import com.example.demo.repository.h2.ProductRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class ProductService {

    private final ProductRepository repository;

    @Transactional(readOnly = true)
    public List<ProductDto> findAll() {
        return repository.findAll().stream().map(this::toDto).toList();
    }

    @Transactional(readOnly = true)
    public ProductDto findById(Long id) {
        return repository.findById(id)
                .map(this::toDto)
                .orElseThrow(() -> new ProductException.ProductNotFoundException(id));
    }

    public ProductDto create(ProductDto dto) {
        if (repository.existsByNameIgnoreCase(dto.getName())) {
            throw new ProductException.ProductAlreadyExistsException(dto.getName());
        }
        Product saved = repository.save(toEntity(dto, null));
        log.info("Created product id={} name={}", saved.getId(), saved.getName());
        return toDto(saved);
    }

    public ProductDto update(Long id, ProductDto dto) {
        Product existing = repository.findById(id)
                .orElseThrow(() -> new ProductException.ProductNotFoundException(id));
        existing.setName(dto.getName());
        existing.setDescription(dto.getDescription());
        existing.setPrice(dto.getPrice());
        existing.setStock(dto.getStock());
        Product saved = repository.save(existing);
        log.info("Updated product id={}", saved.getId());
        return toDto(saved);
    }

    public void delete(Long id) {
        if (!repository.existsById(id)) {
            throw new ProductException.ProductNotFoundException(id);
        }
        repository.deleteById(id);
        log.info("Deleted product id={}", id);
    }

    // -- mappers --
    private ProductDto toDto(Product p) {
        return ProductDto.builder()
                .id(p.getId())
                .name(p.getName())
                .description(p.getDescription())
                .price(p.getPrice())
                .stock(p.getStock())
                .build();
    }

    private Product toEntity(ProductDto d, Long id) {
        return Product.builder()
                .id(id)
                .name(d.getName())
                .description(d.getDescription())
                .price(d.getPrice())
                .stock(d.getStock())
                .build();
    }
}
