package com.example.demo.exception;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

/**
 * Consistent error envelope returned by GlobalExceptionHandler.
 * Follows the spirit of RFC 7807 (Problem Details) but flat and friendly.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "Standard error envelope")
public class ErrorResponse {

    @Schema(example = "2026-05-06T15:00:00Z")
    private Instant timestamp;

    @Schema(example = "404")
    private int status;

    @Schema(example = "Not Found")
    private String error;

    @Schema(example = "PRODUCT_NOT_FOUND", description = "Stable, machine-readable error code")
    private String code;

    @Schema(example = "Product not found with id: 42")
    private String message;

    @Schema(example = "/api/products/42")
    private String path;

    @Schema(description = "Field-level validation errors, when applicable")
    private List<FieldErrorDetail> fieldErrors;

    @Schema(example = "8c1e2c3a-...", description = "Audit trace id correlating server logs")
    private String traceId;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FieldErrorDetail {
        private String field;
        private String message;
        private Object rejectedValue;
    }
}
