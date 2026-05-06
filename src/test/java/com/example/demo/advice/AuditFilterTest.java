package com.example.demo.advice;

import com.example.demo.IntegrationTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.test.context.support.WithMockUser;

import java.util.UUID;

import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.equalTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AuditFilterTest extends IntegrationTestBase {

    private static final String UUID_REGEX =
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}";

    @Test
    @DisplayName("when no incoming X-Trace-Id header, the filter generates a UUID and echoes it back")
    @WithMockUser(roles = "USER")
    void generatesTraceId_whenAbsent() throws Exception {
        mockMvc.perform(get("/api/health/secure"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Trace-Id", matchesPattern(UUID_REGEX)));
    }

    @Test
    @DisplayName("when X-Trace-Id is supplied by the caller, the filter preserves and echoes it")
    @WithMockUser(roles = "USER")
    void preservesIncomingTraceId() throws Exception {
        String traceId = UUID.randomUUID().toString();
        mockMvc.perform(get("/api/health/secure").header("X-Trace-Id", traceId))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Trace-Id", equalTo(traceId)));
    }

    @Test
    @DisplayName("error responses also carry the traceId in the body")
    @WithMockUser(roles = "USER")
    void errorBody_hasTraceId() throws Exception {
        String traceId = UUID.randomUUID().toString();
        mockMvc.perform(get("/api/products/99999").header("X-Trace-Id", traceId))
                .andExpect(status().isNotFound())
                .andExpect(header().string("X-Trace-Id", equalTo(traceId)))
                .andExpect(jsonPath("$.traceId").value(traceId));
    }
}
