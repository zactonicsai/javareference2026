package com.example.demo.service;

import com.example.demo.dto.UploadedFileDto;
import com.example.demo.entity.h2.UploadedFile;
import com.example.demo.exception.FileException;
import com.example.demo.repository.h2.UploadedFileRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@Profile("!worker")
@RequiredArgsConstructor
public class FileService {

    private final S3Client s3Client;
    private final S3Presigner s3Presigner;
    private final UploadedFileRepository repository;

    @Value("${app.s3.bucket:demo-uploads}")
    private String bucket;

    @Value("${app.upload.max-bytes:10485760}")  // 10 MB
    private long maxBytes;

    @Transactional("h2TransactionManager")
    public UploadedFileDto upload(MultipartFile file, String username) {
        if (file == null || file.isEmpty()) {
            throw new FileException.EmptyFileException();
        }
        if (file.getSize() > maxBytes) {
            throw new FileException.FileTooLargeException(file.getSize(), maxBytes);
        }

        String s3Key = "uploads/" + UUID.randomUUID() + "-" + safeName(file.getOriginalFilename());
        try (InputStream in = file.getInputStream()) {
            s3Client.putObject(
                    PutObjectRequest.builder()
                            .bucket(bucket)
                            .key(s3Key)
                            .contentType(file.getContentType())
                            .contentLength(file.getSize())
                            .build(),
                    RequestBody.fromInputStream(in, file.getSize()));
        } catch (IOException | S3Exception e) {
            throw new FileException.StorageException(e.getMessage(), e);
        }

        UploadedFile saved = repository.save(UploadedFile.builder()
                .originalName(file.getOriginalFilename())
                .s3Key(s3Key)
                .contentType(file.getContentType())
                .sizeBytes(file.getSize())
                .uploadedBy(username)
                .build());

        log.info("Uploaded file id={} key={} size={} by={}",
                saved.getId(), saved.getS3Key(), saved.getSizeBytes(), username);
        return toDto(saved, false);
    }

    @Transactional(value = "h2TransactionManager", readOnly = true)
    public List<UploadedFileDto> list() {
        return repository.findAll().stream()
                .map(f -> toDto(f, false))
                .toList();
    }

    @Transactional(value = "h2TransactionManager", readOnly = true)
    public UploadedFileDto getMetadata(Long id) {
        UploadedFile f = repository.findById(id)
                .orElseThrow(() -> new FileException.FileNotFoundException(id));
        return toDto(f, true);
    }

    @Transactional(value = "h2TransactionManager", readOnly = true)
    public DownloadResult download(Long id) {
        UploadedFile f = repository.findById(id)
                .orElseThrow(() -> new FileException.FileNotFoundException(id));
        try {
            ResponseInputStream<GetObjectResponse> resp = s3Client.getObject(
                    GetObjectRequest.builder().bucket(bucket).key(f.getS3Key()).build());
            return new DownloadResult(resp, f);
        } catch (S3Exception e) {
            throw new FileException.StorageException(e.getMessage(), e);
        }
    }

    @Transactional("h2TransactionManager")
    public void delete(Long id) {
        UploadedFile f = repository.findById(id)
                .orElseThrow(() -> new FileException.FileNotFoundException(id));
        try {
            s3Client.deleteObject(DeleteObjectRequest.builder()
                    .bucket(bucket).key(f.getS3Key()).build());
        } catch (S3Exception e) {
            throw new FileException.StorageException(e.getMessage(), e);
        }
        repository.delete(f);
    }

    // -- helpers --

    private String presignedUrl(String s3Key) {
        try {
            GetObjectPresignRequest req = GetObjectPresignRequest.builder()
                    .signatureDuration(Duration.ofMinutes(15))
                    .getObjectRequest(GetObjectRequest.builder()
                            .bucket(bucket).key(s3Key).build())
                    .build();
            return s3Presigner.presignGetObject(req).url().toString();
        } catch (Exception e) {
            log.warn("Failed to presign URL for {}: {}", s3Key, e.getMessage());
            return null;
        }
    }

    private UploadedFileDto toDto(UploadedFile f, boolean includePresigned) {
        return UploadedFileDto.builder()
                .id(f.getId())
                .originalName(f.getOriginalName())
                .s3Key(f.getS3Key())
                .contentType(f.getContentType())
                .sizeBytes(f.getSizeBytes())
                .uploadedBy(f.getUploadedBy())
                .uploadedAt(f.getUploadedAt())
                .downloadUrl(includePresigned ? presignedUrl(f.getS3Key()) : null)
                .build();
    }

    private String safeName(String original) {
        if (original == null || original.isBlank()) return "unnamed";
        return original.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    public record DownloadResult(ResponseInputStream<GetObjectResponse> stream, UploadedFile metadata) { }
}
