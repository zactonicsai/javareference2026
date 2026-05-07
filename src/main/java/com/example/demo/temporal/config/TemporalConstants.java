package com.example.demo.temporal.config;

/**
 * Centralized constants for Temporal task queues and workflow id prefixes.
 * Keeping these in a single class prevents typos between client and worker code.
 */
public final class TemporalConstants {

    /** Single task queue used by all product workflows in this demo. */
    public static final String PRODUCT_TASK_QUEUE = "product-task-queue";

    public static final String CREATE_WF_PREFIX = "product-create-";
    public static final String UPDATE_WF_PREFIX = "product-update-";
    public static final String DELETE_WF_PREFIX = "product-delete-";

    private TemporalConstants() { }
}
