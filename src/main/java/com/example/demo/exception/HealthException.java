package com.example.demo.exception;

import org.springframework.http.HttpStatus;

/** Base exception for the Health controller. */
public class HealthException extends BaseAppException {
    public HealthException(String message, HttpStatus status, String errorCode) {
        super(message, status, errorCode);
    }

    public static class HealthCheckFailedException extends HealthException {
        public HealthCheckFailedException(String detail) {
            super("Health check failed: " + detail,
                    HttpStatus.SERVICE_UNAVAILABLE, "HEALTH_CHECK_FAILED");
        }
    }
}
