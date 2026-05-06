package com.example.demo.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * Base for all application-thrown exceptions.
 * Carries an HTTP status and a machine-readable error code so the
 * GlobalExceptionHandler can return a consistent ErrorResponse.
 */
@Getter
public abstract class BaseAppException extends RuntimeException {
    private final HttpStatus status;
    private final String errorCode;

    protected BaseAppException(String message, HttpStatus status, String errorCode) {
        super(message);
        this.status = status;
        this.errorCode = errorCode;
    }

    protected BaseAppException(String message, HttpStatus status, String errorCode, Throwable cause) {
        super(message, cause);
        this.status = status;
        this.errorCode = errorCode;
    }
}
