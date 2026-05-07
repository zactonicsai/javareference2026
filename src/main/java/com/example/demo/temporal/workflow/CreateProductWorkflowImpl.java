package com.example.demo.temporal.workflow;

import com.example.demo.dto.ProductDto;
import com.example.demo.temporal.activity.ProductActivities;
import com.example.demo.temporal.config.TemporalConstants;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.spring.boot.WorkflowImpl;
import io.temporal.workflow.Workflow;

import java.time.Duration;

@WorkflowImpl(taskQueues = TemporalConstants.PRODUCT_TASK_QUEUE)
public class CreateProductWorkflowImpl implements CreateProductWorkflow {

    /**
     * Activity stub. Auto-retried on transient failure; non-retryable on
     * stable business failures like PRODUCT_ALREADY_EXISTS.
     */
    private final ProductActivities activities = Workflow.newActivityStub(
            ProductActivities.class,
            ActivityOptions.newBuilder()
                    .setStartToCloseTimeout(Duration.ofSeconds(10))
                    .setRetryOptions(RetryOptions.newBuilder()
                            .setInitialInterval(Duration.ofSeconds(1))
                            .setMaximumInterval(Duration.ofSeconds(10))
                            .setBackoffCoefficient(2.0)
                            .setMaximumAttempts(3)
                            // Stable business errors: do not retry
                            .setDoNotRetry(
                                    "PRODUCT_ALREADY_EXISTS",
                                    "PRODUCT_NOT_FOUND")
                            .build())
                    .build());

    @Override
    public ProductDto create(ProductDto dto) {
        String workflowId = Workflow.getInfo().getWorkflowId();
        return activities.saveToPostgres(dto, workflowId);
    }
}
