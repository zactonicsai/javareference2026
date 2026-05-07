package com.example.demo.temporal.activity;

import com.example.demo.dto.ProductDto;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

/**
 * Activities are the only place where I/O is allowed in Temporal.
 * These methods are run on the worker and persist data to Postgres.
 */
@ActivityInterface
public interface ProductActivities {

    @ActivityMethod
    ProductDto saveToPostgres(ProductDto dto, String workflowId);

    @ActivityMethod
    ProductDto updateInPostgres(Long id, ProductDto dto, String workflowId);

    @ActivityMethod
    void deleteFromPostgres(Long id);

    @ActivityMethod
    boolean existsByName(String name);
}
