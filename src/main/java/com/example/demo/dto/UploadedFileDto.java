package com.example.demo.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Metadata for an uploaded file stored in S3")
public class UploadedFileDto {
    private Long id;
    private String originalName;
    private String s3Key;
    private String contentType;
    private Long sizeBytes;
    private String uploadedBy;
    private Instant uploadedAt;

    @Schema(description = "Pre-signed download URL (only set on detail responses)")
    private String downloadUrl;
}
