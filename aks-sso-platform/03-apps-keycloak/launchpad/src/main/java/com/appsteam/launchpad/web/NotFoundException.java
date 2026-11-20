package com.appsteam.launchpad.web;

/** Unknown service or project id → 404 page. */
public class NotFoundException extends RuntimeException {
    public NotFoundException(String message) {
        super(message);
    }
}
