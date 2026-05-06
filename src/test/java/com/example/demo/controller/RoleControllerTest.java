package com.example.demo.controller;

import com.example.demo.IntegrationTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.test.context.support.WithMockUser;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class RoleControllerTest extends IntegrationTestBase {

    @Test
    @DisplayName("anonymous → 401 with UNAUTHENTICATED code")
    void anonymous_isUnauthorized() throws Exception {
        mockMvc.perform(get("/api/role/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.path").value("/api/role/me"))
                .andExpect(jsonPath("$.timestamp").value(notNullValue()));
    }

    @Test
    @DisplayName("ADMIN gets AdminDto with permissions and systemMetrics")
    @WithMockUser(username = "admin", roles = {"ADMIN", "MANAGER", "USER"})
    void admin_getsAdminDto() throws Exception {
        mockMvc.perform(get("/api/role/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("ADMIN"))
                .andExpect(jsonPath("$.username").value("admin"))
                .andExpect(jsonPath("$.permissions",
                        containsInAnyOrder("USER_MANAGE", "PRODUCT_DELETE", "AUDIT_READ", "ACTUATOR_FULL")))
                .andExpect(jsonPath("$.systemMetrics.activeSessions").value(42))
                .andExpect(jsonPath("$.message").exists())
                // ManagerDto-only field absent
                .andExpect(jsonPath("$.directReports").doesNotExist())
                .andExpect(jsonPath("$.budgetUsd").doesNotExist());
    }

    @Test
    @DisplayName("MANAGER gets ManagerDto wrapping a PersonDto")
    @WithMockUser(username = "manager", roles = {"MANAGER", "USER"})
    void manager_getsManagerDto() throws Exception {
        mockMvc.perform(get("/api/role/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("MANAGER"))
                .andExpect(jsonPath("$.person.fullName").value("Maria Manager"))
                .andExpect(jsonPath("$.person.email").value("manager@example.com"))
                .andExpect(jsonPath("$.person.department").value("Operations"))
                .andExpect(jsonPath("$.teamName").value("Sales North-East"))
                .andExpect(jsonPath("$.directReports.length()").value(4))
                .andExpect(jsonPath("$.budgetUsd").value(150000.00))
                // Admin-only field absent
                .andExpect(jsonPath("$.permissions").doesNotExist())
                .andExpect(jsonPath("$.systemMetrics").doesNotExist());
    }

    @Test
    @DisplayName("USER gets UserDto with visibleItems")
    @WithMockUser(username = "user", roles = {"USER"})
    void user_getsUserDto() throws Exception {
        mockMvc.perform(get("/api/role/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("USER"))
                .andExpect(jsonPath("$.username").value("user"))
                .andExpect(jsonPath("$.displayName").value("Standard User"))
                .andExpect(jsonPath("$.visibleItems",
                        containsInAnyOrder("Dashboard", "My Profile", "Help Center")))
                // Admin/Manager-only fields absent
                .andExpect(jsonPath("$.permissions").doesNotExist())
                .andExpect(jsonPath("$.person").doesNotExist())
                .andExpect(jsonPath("$.budgetUsd").doesNotExist());
    }

    @Test
    @DisplayName("response carries an X-Trace-Id header for audit correlation")
    @WithMockUser(username = "user", roles = {"USER"})
    void responseIncludesTraceId() throws Exception {
        mockMvc.perform(get("/api/role/me"))
                .andExpect(status().isOk())
                .andExpect(header().exists("X-Trace-Id"));
    }
}
