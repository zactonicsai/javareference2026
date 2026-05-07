package com.example.demo.service;

import com.example.demo.dto.ProductDto;
import com.example.demo.entity.pg.ProductPg;
import com.example.demo.exception.ProductException;
import com.example.demo.repository.pg.ProductPgRepository;
import com.example.demo.temporal.config.TemporalConstants;
import com.example.demo.temporal.workflow.CreateProductWorkflow;
import com.example.demo.temporal.workflow.DeleteProductWorkflow;
import com.example.demo.temporal.workflow.UpdateProductWorkflow;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowFailedException;
import io.temporal.client.WorkflowOptions;
import io.temporal.failure.ApplicationFailure;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * Client-side service for the Temporal-backed CRUD endpoints.
 *
 * Pattern (CQRS-lite):
 *  - Writes (create/update/delete) are durable workflows started from the API process,
 *    executed by activities running in the worker process, persisted to Postgres.
 *  - Reads are direct Postgres queries. There's no value in routing reads through Temporal.
 *
 * Failure handling:
 *  - Validation/business errors (PRODUCT_NOT_FOUND, PRODUCT_ALREADY_EXISTS) are thrown by
 *    the activity as Temporal ApplicationFailure with a stable type. We translate them
 *    back to typed domain exceptions so the GlobalExceptionHandler returns the right code.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProductTemporalService {

    private final WorkflowClient workflowClient;
    private final ProductPgRepository repository;

    // -- read path (direct Postgres) --

    @Transactional(value = "pgTransactionManager", readOnly = true)
    public List<ProductDto> findAll() {
        return repository.findAll().stream().map(this::toDto).toList();
    }

    @Transactional(value = "pgTransactionManager", readOnly = true)
    public ProductDto findById(Long id) {
        return repository.findById(id)
                .map(this::toDto)
                .orElseThrow(() -> new ProductException.ProductNotFoundException(id));
    }

    // -- write path (Temporal workflows) --

    public ProductDto create(ProductDto dto) {
        String workflowId = TemporalConstants.CREATE_WF_PREFIX + UUID.randomUUID();
        CreateProductWorkflow stub = workflowClient.newWorkflowStub(
                CreateProductWorkflow.class, optionsFor(workflowId));
        log.info("Starting CreateProductWorkflow workflowId={} name={}", workflowId, dto.getName());
        return runOrTranslate(() -> stub.create(dto));
    }

    public ProductDto update(Long id, ProductDto dto) {
        String workflowId = TemporalConstants.UPDATE_WF_PREFIX + id + "-" + UUID.randomUUID();
        UpdateProductWorkflow stub = workflowClient.newWorkflowStub(
                UpdateProductWorkflow.class, optionsFor(workflowId));
        log.info("Starting UpdateProductWorkflow workflowId={} id={}", workflowId, id);
        return runOrTranslate(() -> stub.update(id, dto));
    }

    public void delete(Long id) {
        String workflowId = TemporalConstants.DELETE_WF_PREFIX + id + "-" + UUID.randomUUID();
        DeleteProductWorkflow stub = workflowClient.newWorkflowStub(
                DeleteProductWorkflow.class, optionsFor(workflowId));
        log.info("Starting DeleteProductWorkflow workflowId={} id={}", workflowId, id);
        runOrTranslate(() -> { stub.delete(id); return null; });
    }

    // -- helpers --

    private WorkflowOptions optionsFor(String workflowId) {
        return WorkflowOptions.newBuilder()
                .setTaskQueue(TemporalConstants.PRODUCT_TASK_QUEUE)
                .setWorkflowId(workflowId)
                .setWorkflowExecutionTimeout(Duration.ofMinutes(1))
                .build();
    }

    /**
     * Runs the workflow and translates Temporal errors into our typed exceptions
     * so the global handler can produce the right HTTP status + error code.
     */
    private <T> T runOrTranslate(java.util.concurrent.Callable<T> call) {
        try {
            return call.call();
        } catch (WorkflowFailedException wfe) {
            Throwable cause = wfe.getCause();
            if (cause instanceof ApplicationFailure af) {
                throw translate(af);
            }
            throw new ProductException.TemporalExecutionException(wfe.getMessage());
        } catch (RuntimeException re) {
            throw re;
        } catch (Exception e) {
            throw new ProductException.TemporalExecutionException(e.getMessage());
        }
    }

    private ProductException translate(ApplicationFailure af) {
        String type = af.getType();
        String msg = af.getOriginalMessage() == null ? "Activity failure" : af.getOriginalMessage();
        return switch (type == null ? "" : type) {
            case "PRODUCT_NOT_FOUND" -> {
                Long id = parseTrailingId(msg);
                yield new ProductException.ProductNotFoundException(id);
            }
            case "PRODUCT_ALREADY_EXISTS" -> {
                String name = msg.contains(":") ? msg.substring(msg.indexOf(':') + 1).trim() : "(unknown)";
                yield new ProductException.ProductAlreadyExistsException(name);
            }
            default -> new ProductException.TemporalExecutionException(msg);
        };
    }

    private Long parseTrailingId(String message) {
        try {
            String[] tokens = message.split("\\s+");
            return Long.parseLong(tokens[tokens.length - 1]);
        } catch (Exception e) {
            return -1L;
        }
    }

    private ProductDto toDto(ProductPg p) {
        return ProductDto.builder()
                .id(p.getId())
                .name(p.getName())
                .description(p.getDescription())
                .price(p.getPrice())
                .stock(p.getStock())
                .build();
    }
}
