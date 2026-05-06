package com.example.demo.exception;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;

class ExceptionHierarchyTest {

    @Test
    void productNotFound_carriesStatusAndCode() {
        ProductException.ProductNotFoundException ex =
                new ProductException.ProductNotFoundException(42L);

        assertThat(ex).isInstanceOf(ProductException.class);
        assertThat(ex).isInstanceOf(BaseAppException.class);
        assertThat(ex.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(ex.getErrorCode()).isEqualTo("PRODUCT_NOT_FOUND");
        assertThat(ex.getMessage()).contains("42");
    }

    @Test
    void productAlreadyExists_returns409() {
        ProductException.ProductAlreadyExistsException ex =
                new ProductException.ProductAlreadyExistsException("Wireless Mouse");

        assertThat(ex.getStatus()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(ex.getErrorCode()).isEqualTo("PRODUCT_ALREADY_EXISTS");
    }

    @Test
    void unknownRole_returns403() {
        RoleException.UnknownRoleException ex =
                new RoleException.UnknownRoleException("ghost");

        assertThat(ex).isInstanceOf(RoleException.class);
        assertThat(ex.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(ex.getErrorCode()).isEqualTo("ROLE_UNKNOWN");
        assertThat(ex.getMessage()).contains("ghost");
    }

    @Test
    void healthCheckFailed_returns503() {
        HealthException.HealthCheckFailedException ex =
                new HealthException.HealthCheckFailedException("redis down");

        assertThat(ex.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(ex.getErrorCode()).isEqualTo("HEALTH_CHECK_FAILED");
    }
}
