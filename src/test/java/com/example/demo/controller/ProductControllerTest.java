package com.example.demo.controller;

import com.example.demo.IntegrationTestBase;
import com.example.demo.dto.ProductDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;

import java.math.BigDecimal;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ProductControllerTest extends IntegrationTestBase {

    // ---------- LIST / GET ----------

    @Test
    @DisplayName("GET /api/products without auth → 401 with UNAUTHENTICATED")
    void list_anonymous_unauthorized() throws Exception {
        mockMvc.perform(get("/api/products"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    @DisplayName("GET /api/products as USER → 200 with seeded items")
    @WithMockUser(roles = "USER")
    void list_asUser_returnsSeed() throws Exception {
        mockMvc.perform(get("/api/products"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()", greaterThanOrEqualTo(4)))
                .andExpect(jsonPath("$[0].id").exists())
                .andExpect(jsonPath("$[0].name").exists())
                .andExpect(jsonPath("$[0].price").exists());
    }

    @Test
    @DisplayName("GET /api/products/{id} with unknown id → 404 PRODUCT_NOT_FOUND")
    @WithMockUser(roles = "USER")
    void getById_missing_returnsTypedError() throws Exception {
        mockMvc.perform(get("/api/products/99999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PRODUCT_NOT_FOUND"))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.message", containsString("99999")))
                .andExpect(jsonPath("$.path").value("/api/products/99999"));
    }

    // ---------- CREATE ----------

    @Test
    @DisplayName("POST as USER → 403 ACCESS_DENIED (only MANAGER+ can create)")
    @WithMockUser(roles = "USER")
    void create_asUser_forbidden() throws Exception {
        ProductDto dto = ProductDto.builder()
                .name("Test").description("x").price(new BigDecimal("9.99")).stock(1).build();

        mockMvc.perform(post("/api/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    @Test
    @DisplayName("POST as MANAGER with valid body → 201 + Location header")
    @WithMockUser(roles = {"MANAGER", "USER"})
    void create_asManager_created() throws Exception {
        ProductDto dto = ProductDto.builder()
                .name("Standing Desk")
                .description("Adjustable, electric")
                .price(new BigDecimal("499.00"))
                .stock(15)
                .build();

        mockMvc.perform(post("/api/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", containsString("/api/products/")))
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.name").value("Standing Desk"))
                .andExpect(jsonPath("$.price").value(499.00));
    }

    @Test
    @DisplayName("POST duplicate name → 409 PRODUCT_ALREADY_EXISTS")
    @WithMockUser(roles = {"MANAGER", "USER"})
    void create_duplicate_conflict() throws Exception {
        ProductDto dto = ProductDto.builder()
                .name("Wireless Mouse") // already in seed data
                .description("dup")
                .price(new BigDecimal("1.00"))
                .stock(1)
                .build();

        mockMvc.perform(post("/api/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PRODUCT_ALREADY_EXISTS"));
    }

    @Test
    @DisplayName("POST invalid body → 400 VALIDATION_FAILED with field errors")
    @WithMockUser(roles = {"MANAGER", "USER"})
    void create_invalid_validationFails() throws Exception {
        // blank name, negative price, null stock — three field violations
        String body = """
                {"name":"","description":"x","price":-1,"stock":null}
                """;

        mockMvc.perform(post("/api/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors.length()", greaterThanOrEqualTo(2)))
                .andExpect(jsonPath("$.fieldErrors[*].field").exists());
    }

    @Test
    @DisplayName("POST malformed JSON → 400 MALFORMED_JSON")
    @WithMockUser(roles = {"MANAGER", "USER"})
    void create_malformedJson() throws Exception {
        mockMvc.perform(post("/api/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{broken"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_JSON"));
    }

    // ---------- UPDATE ----------

    @Test
    @DisplayName("PUT existing product as MANAGER → 200 with updated fields")
    @WithMockUser(roles = {"MANAGER", "USER"})
    void update_asManager_updates() throws Exception {
        ProductDto dto = ProductDto.builder()
                .name("Wireless Mouse")
                .description("Updated by test")
                .price(new BigDecimal("34.99"))
                .stock(50)
                .build();

        mockMvc.perform(put("/api/products/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.description").value("Updated by test"))
                .andExpect(jsonPath("$.price").value(34.99));
    }

    @Test
    @DisplayName("PUT missing product → 404 PRODUCT_NOT_FOUND")
    @WithMockUser(roles = {"MANAGER", "USER"})
    void update_missing_404() throws Exception {
        ProductDto dto = ProductDto.builder()
                .name("Ghost").description("x").price(new BigDecimal("1.00")).stock(1).build();

        mockMvc.perform(put("/api/products/99999")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PRODUCT_NOT_FOUND"));
    }

    // ---------- DELETE ----------

    @Test
    @DisplayName("DELETE as MANAGER → 403 ACCESS_DENIED (admin only)")
    @WithMockUser(roles = {"MANAGER", "USER"})
    void delete_asManager_forbidden() throws Exception {
        mockMvc.perform(delete("/api/products/2"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    @Test
    @DisplayName("DELETE as ADMIN → 204 then GET → 404")
    @WithMockUser(roles = {"ADMIN", "MANAGER", "USER"})
    void delete_asAdmin_thenMissing() throws Exception {
        // create something disposable first
        ProductDto dto = ProductDto.builder()
                .name("Disposable Gadget").description("temp").price(new BigDecimal("9.99")).stock(1).build();

        var result = mockMvc.perform(post("/api/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isCreated())
                .andReturn();

        ProductDto created = objectMapper.readValue(result.getResponse().getContentAsString(), ProductDto.class);

        mockMvc.perform(delete("/api/products/" + created.getId()))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/products/" + created.getId()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PRODUCT_NOT_FOUND"));
    }
}
