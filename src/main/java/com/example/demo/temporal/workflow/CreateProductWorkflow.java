package com.example.demo.temporal.workflow;

import com.example.demo.dto.ProductDto;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

@WorkflowInterface
public interface CreateProductWorkflow {
    @WorkflowMethod
    ProductDto create(ProductDto dto);
}
