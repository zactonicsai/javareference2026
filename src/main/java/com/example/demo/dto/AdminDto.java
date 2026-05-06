package com.example.demo.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Admin payload — system-level data")
public class AdminDto {
    @Schema(example = "ADMIN")
    private String role;

    @Schema(example = "admin")
    private String username;

    private Instant issuedAt;

    @Schema(description = "List of system permissions")
    private List<String> permissions;

    @Schema(description = "System-wide metrics snapshot")
    private Map<String, Object> systemMetrics;

    @Schema(example = "Welcome, system administrator. You have full control.")
    private String message;
}
