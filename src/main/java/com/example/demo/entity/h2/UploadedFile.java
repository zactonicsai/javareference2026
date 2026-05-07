package com.example.demo.entity.h2;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "uploaded_files")
@EntityListeners(AuditingEntityListener.class)
public class UploadedFile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "original_name", nullable = false, length = 255)
    private String originalName;

    @Column(name = "s3_key", nullable = false, unique = true, length = 255)
    private String s3Key;

    @Column(name = "content_type", length = 100)
    private String contentType;

    @Column(name = "size_bytes", nullable = false)
    private Long sizeBytes;

    @Column(name = "uploaded_by", length = 100)
    private String uploadedBy;

    @CreatedDate
    @Column(name = "uploaded_at", nullable = false, updatable = false)
    private Instant uploadedAt;
}
