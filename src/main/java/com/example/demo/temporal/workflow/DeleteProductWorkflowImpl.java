package com.example.demo.temporal.workflow;

import com.example.demo.temporal.activity.ProductActivities;
import com.example.demo.temporal.config.TemporalConstants;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.spring.boot.WorkflowImpl;
import io.temporal.workflow.Workflow;

import java.time.Duration;

@WorkflowImpl(taskQueues = TemporalConstants.PRODUCT_TASK_QUEUE)
public class DeleteProductWorkflowImpl implements DeleteProductWorkflow {

    private final ProductActivities activities = Workflow.newActivityStub(
            ProductActivities.class,
            ActivityOptions.newBuilder()
                    .setStartToCloseTimeout(Duration.ofSeconds(10))
                    .setRetryOptions(RetryOptions.newBuilder()
                            .setMaximumAttempts(3)
                            .setDoNotRetry("PRODUCT_NOT_FOUND")
                            .build())
                    .build());

    @Override
    public void delete(Long id) {
        activities.deleteFromPostgres(id);
    }
}
