package com.example.demo.controller;

import com.example.demo.dto.UploadedFileDto;
import com.example.demo.service.FileService;
import com.example.demo.service.FileService.DownloadResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/api/files")
@RequiredArgsConstructor
@Tag(name = "Files", description = "Upload to LocalStack S3, metadata stored in H2")
public class FileController {

    private final FileService service;

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Upload a file (any authenticated user)")
    public UploadedFileDto upload(@RequestParam("file") MultipartFile file, Authentication auth) {
        return service.upload(file, auth == null ? "anonymous" : auth.getName());
    }

    @GetMapping
    @Operation(summary = "List uploaded file metadata")
    public List<UploadedFileDto> list() {
        return service.list();
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get a single file's metadata + a 15-minute presigned download URL")
    public UploadedFileDto get(@PathVariable Long id) {
        return service.getMetadata(id);
    }

    @GetMapping("/{id}/download")
    @Operation(summary = "Stream the file content directly through the API")
    public ResponseEntity<InputStreamResource> download(@PathVariable Long id, HttpServletResponse res) {
        DownloadResult dr = service.download(id);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + dr.metadata().getOriginalName() + "\"")
                .contentType(MediaType.parseMediaType(
                        dr.metadata().getContentType() == null
                                ? MediaType.APPLICATION_OCTET_STREAM_VALUE
                                : dr.metadata().getContentType()))
                .contentLength(dr.metadata().getSizeBytes())
                .body(new InputStreamResource(dr.stream()));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete the S3 object and its metadata (ADMIN only)")
    public void delete(@PathVariable Long id) {
        service.delete(id);
    }
}
