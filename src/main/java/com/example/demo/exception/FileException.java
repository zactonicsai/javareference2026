package com.example.demo.exception;

import org.springframework.http.HttpStatus;

/** Base for the file upload/download endpoints. */
public class FileException extends BaseAppException {

    public FileException(String message, HttpStatus status, String errorCode) {
        super(message, status, errorCode);
    }

    public FileException(String message, HttpStatus status, String errorCode, Throwable cause) {
        super(message, status, errorCode, cause);
    }

    public static class FileNotFoundException extends FileException {
        public FileNotFoundException(Long id) {
            super("File not found with id: " + id, HttpStatus.NOT_FOUND, "FILE_NOT_FOUND");
        }
    }

    public static class FileTooLargeException extends FileException {
        public FileTooLargeException(long bytes, long max) {
            super("File too large: " + bytes + " bytes (max " + max + ")",
                    HttpStatus.PAYLOAD_TOO_LARGE, "FILE_TOO_LARGE");
        }
    }

    public static class EmptyFileException extends FileException {
        public EmptyFileException() {
            super("Uploaded file is empty", HttpStatus.BAD_REQUEST, "FILE_EMPTY");
        }
    }

    public static class StorageException extends FileException {
        public StorageException(String detail, Throwable cause) {
            super("Storage backend error: " + detail,
                    HttpStatus.BAD_GATEWAY, "FILE_STORAGE_ERROR", cause);
        }
    }
}
