package com.example.groups;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"ADMIN_PASSWORD=Test-only-admin-1234", "USER_PASSWORD=Test-only-user-1234", "USER2_PASSWORD=Test-only-user2-1234"})
@AutoConfigureMockMvc
class SecurityTests {
    @Autowired MockMvc mvc;

    @Test void anonymousCannotReadAppOrSession() throws Exception {
        mvc.perform(get("/")).andExpect(status().isFound()).andExpect(redirectedUrl("http://localhost/login.html"));
        mvc.perform(get("/index.html")).andExpect(status().isFound()).andExpect(redirectedUrl("http://localhost/login.html"));
        mvc.perform(get("/index.html").accept("text/html")).andExpect(status().isFound());
        mvc.perform(get("/api/session").accept("text/html")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/session")).andExpect(status().isUnauthorized());
        mvc.perform(get("/login.html")).andExpect(status().isOk());
        mvc.perform(get("/api/csrf")).andExpect(status().isOk()).andExpect(jsonPath("$.token").isString());
        // Keycloak sign-in is off unless the keycloak profile is active.
        mvc.perform(get("/api/login-options")).andExpect(status().isOk()).andExpect(jsonPath("$.oidc").value(false));
    }
    @ParameterizedTest
    @CsvSource({"admin,dev", "user,dev1", "user2,dev2"})
    void actualLoginAndFullGroupMatrix(String username, String group) throws Exception {
        String password = "Test-only-" + username + "-1234";
        MockHttpSession before = new MockHttpSession();
        String beforeId = before.getId();
        var result = mvc.perform(post("/login").session(before).with(csrf())
            .param("username", username).param("password", password))
            .andExpect(authenticated().withUsername(username)).andExpect(redirectedUrl("/")).andReturn();
        var session = (MockHttpSession) result.getRequest().getSession(false);
        assertNotEquals(beforeId, session.getId(), "Login must rotate the session ID");
        mvc.perform(get("/api/session").session(session)).andExpect(status().isOk())
            .andExpect(jsonPath("$.username").value(username)).andExpect(jsonPath("$.groups[0]").value(group))
            .andExpect(jsonPath("$.sessionId").doesNotExist()).andExpect(jsonPath("$.password").doesNotExist());
        for (String target : new String[]{"dev", "dev1", "dev2"}) {
            int expected = target.equals(group) ? 200 : 403;
            mvc.perform(get("/api/groups/" + target + "/details").session(session)).andExpect(status().is(expected));
            mvc.perform(post("/api/groups/" + target + "/acknowledge").session(session).with(csrf())).andExpect(status().is(expected));
        }
        mvc.perform(get("/api/admin/summary").session(session)).andExpect(status().is(username.equals("admin") ? 200 : 403));
        mvc.perform(post("/api/groups/" + group + "/acknowledge").session(session)).andExpect(status().isForbidden());
        mvc.perform(post("/logout").session(session)).andExpect(status().isForbidden());
        mvc.perform(post("/logout").session(session).with(csrf())).andExpect(redirectedUrl("/login.html?logout"));
        assertTrue(session.isInvalid());
    }
    @Test void loginRequiresCsrfAndCorrectPassword() throws Exception {
        mvc.perform(post("/login").param("username", "admin").param("password", "Test-only-admin-1234"))
            .andExpect(status().isForbidden());
        mvc.perform(post("/login").with(csrf()).param("username", "admin").param("password", "wrong"))
            .andExpect(unauthenticated()).andExpect(redirectedUrl("/login.html?error"));
    }
    @Test void headersAndDenyByDefault() throws Exception {
        mvc.perform(get("/api/session").with(user("admin").authorities(() -> "GROUP_dev")))
            .andExpect(header().string("X-Content-Type-Options", "nosniff"))
            .andExpect(header().string("X-Frame-Options", "DENY"))
            .andExpect(header().exists("Content-Security-Policy"))
            .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")));
        mvc.perform(get("/api/unlisted").with(user("admin"))).andExpect(status().isForbidden());
    }
}
