package com.example.demo.controller;

import com.example.demo.IntegrationTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.test.context.support.WithMockUser;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class HealthControllerTest extends IntegrationTestBase {

    @Test
    @DisplayName("/api/health/public is reachable anonymously")
    void publicEndpoint_anonymous_ok() throws Exception {
        mockMvc.perform(get("/api/health/public"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    @DisplayName("/api/health/secure is 401 anonymously")
    void secureEndpoint_anonymous_401() throws Exception {
        mockMvc.perform(get("/api/health/secure"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    @DisplayName("/api/health/secure exposes runtime details when authenticated")
    @WithMockUser(username = "user", roles = "USER")
    void secureEndpoint_authed_revealsDetails() throws Exception {
        mockMvc.perform(get("/api/health/secure"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.user").value("user"))
                .andExpect(jsonPath("$.javaVersion").exists())
                .andExpect(jsonPath("$.jvmUptimeMs").exists())
                .andExpect(jsonPath("$.processors").exists());
    }

    @Test
    @DisplayName("/actuator/health is publicly reachable, /actuator/metrics requires admin")
    @WithMockUser(roles = "USER")
    void actuator_metrics_forbiddenForNonAdmin() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/actuator/metrics"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }
}
