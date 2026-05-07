package com.example.demo.temporal.activity;

import com.example.demo.dto.ProductDto;
import com.example.demo.entity.pg.ProductPg;
import com.example.demo.exception.ProductException;
import com.example.demo.repository.pg.ProductPgRepository;
import com.example.demo.temporal.config.TemporalConstants;
import io.temporal.failure.ApplicationFailure;
import io.temporal.spring.boot.ActivityImpl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Activities are plain Spring beans. The @ActivityImpl annotation registers them
 * with the Temporal worker bound to the given task queue.
 *
 * Best practices applied:
 *  - Idempotency where possible (existsByName check before insert)
 *  - Translate domain exceptions to Temporal ApplicationFailure with stable
 *    types — this lets workflows decide whether to retry
 *  - @Transactional on writes; uses the Postgres TransactionManager qualifier
 *    so we don't accidentally touch the H2 transaction context
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ActivityImpl(taskQueues = TemporalConstants.PRODUCT_TASK_QUEUE)
public class ProductActivitiesImpl implements ProductActivities {

    private final ProductPgRepository repository;

    @Override
    @Transactional("pgTransactionManager")
    public ProductDto saveToPostgres(ProductDto dto, String workflowId) {
        log.info("[activity] saveToPostgres name={} workflowId={}", dto.getName(), workflowId);
        if (repository.existsByNameIgnoreCase(dto.getName())) {
            throw ApplicationFailure.newNonRetryableFailure(
                    "Product already exists with name: " + dto.getName(),
                    "PRODUCT_ALREADY_EXISTS");
        }
        ProductPg saved = repository.save(toEntity(dto, null, workflowId));
        return toDto(saved);
    }

    @Override
    @Transactional("pgTransactionManager")
    public ProductDto updateInPostgres(Long id, ProductDto dto, String workflowId) {
        log.info("[activity] updateInPostgres id={} workflowId={}", id, workflowId);
        ProductPg existing = repository.findById(id)
                .orElseThrow(() -> ApplicationFailure.newNonRetryableFailure(
                        "Product not found with id: " + id, "PRODUCT_NOT_FOUND"));
        existing.setName(dto.getName());
        existing.setDescription(dto.getDescription());
        existing.setPrice(dto.getPrice());
        existing.setStock(dto.getStock());
        existing.setLastWorkflowId(workflowId);
        return toDto(repository.save(existing));
    }

    @Override
    @Transactional("pgTransactionManager")
    public void deleteFromPostgres(Long id) {
        log.info("[activity] deleteFromPostgres id={}", id);
        if (!repository.existsById(id)) {
            throw ApplicationFailure.newNonRetryableFailure(
                    "Product not found with id: " + id, "PRODUCT_NOT_FOUND");
        }
        repository.deleteById(id);
    }

    @Override
    @Transactional(value = "pgTransactionManager", readOnly = true)
    public boolean existsByName(String name) {
        return repository.existsByNameIgnoreCase(name);
    }

    // -- mapping --
    private ProductDto toDto(ProductPg p) {
        return ProductDto.builder()
                .id(p.getId())
                .name(p.getName())
                .description(p.getDescription())
                .price(p.getPrice())
                .stock(p.getStock())
                .build();
    }

    private ProductPg toEntity(ProductDto d, Long id, String workflowId) {
        return ProductPg.builder()
                .id(id)
                .name(d.getName())
                .description(d.getDescription())
                .price(d.getPrice())
                .stock(d.getStock())
                .lastWorkflowId(workflowId)
                .build();
    }

    /** Map non-retryable activity failures back to a domain exception. */
    public static ProductException mapApplicationFailure(ApplicationFailure af) {
        String type = af.getType();
        return switch (type == null ? "" : type) {
            case "PRODUCT_NOT_FOUND" -> new ProductException.ProductNotFoundException(-1L);
            case "PRODUCT_ALREADY_EXISTS" -> new ProductException.ProductAlreadyExistsException("(see logs)");
            default -> new ProductException.ProductValidationException(
                    af.getOriginalMessage() == null ? "Activity failed" : af.getOriginalMessage());
        };
    }
}
