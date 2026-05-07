package com.example.demo.temporal.workflow;

import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

@WorkflowInterface
public interface DeleteProductWorkflow {
    @WorkflowMethod
    void delete(Long id);
}
