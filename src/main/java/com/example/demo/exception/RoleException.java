package com.example.demo.exception;

import org.springframework.http.HttpStatus;

/** Base exception for the Role controller. */
public class RoleException extends BaseAppException {
    public RoleException(String message, HttpStatus status, String errorCode) {
        super(message, status, errorCode);
    }

    /** Sub-exception: cannot resolve role for the authenticated principal. */
    public static class UnknownRoleException extends RoleException {
        public UnknownRoleException(String username) {
            super("No matching role found for user: " + username,
                    HttpStatus.FORBIDDEN, "ROLE_UNKNOWN");
        }
    }
}
