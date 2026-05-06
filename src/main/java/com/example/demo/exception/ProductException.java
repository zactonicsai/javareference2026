package com.example.demo.exception;

import org.springframework.http.HttpStatus;

/** Base exception for the Product CRUD controller. */
public class ProductException extends BaseAppException {

    public ProductException(String message, HttpStatus status, String errorCode) {
        super(message, status, errorCode);
    }

    public static class ProductNotFoundException extends ProductException {
        public ProductNotFoundException(Long id) {
            super("Product not found with id: " + id, HttpStatus.NOT_FOUND, "PRODUCT_NOT_FOUND");
        }
    }

    public static class ProductAlreadyExistsException extends ProductException {
        public ProductAlreadyExistsException(String name) {
            super("Product already exists with name: " + name,
                    HttpStatus.CONFLICT, "PRODUCT_ALREADY_EXISTS");
        }
    }

    public static class ProductValidationException extends ProductException {
        public ProductValidationException(String detail) {
            super(detail, HttpStatus.BAD_REQUEST, "PRODUCT_INVALID");
        }
    }
}
