package com.example.groups;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Keycloak sign-in switched on. No Keycloak server is needed: nothing here contacts it. */
@SpringBootTest(properties = {"ADMIN_PASSWORD=Test-only-admin-1234", "USER_PASSWORD=Test-only-user-1234", "USER2_PASSWORD=Test-only-user2-1234",
    "keycloak.enabled=true", "keycloak.issuer-uri=http://localhost:8081/realms/workspace", "keycloak.client-id=team-workspace",
    "keycloak.client-secret=test-secret", "server.servlet.session.cookie.same-site=lax"})
@AutoConfigureMockMvc
class KeycloakTests {
    @Autowired MockMvc mvc;

    @Test void loginPageIsToldToShowTheKeycloakLink() throws Exception {
        mvc.perform(get("/api/login-options")).andExpect(status().isOk())
            .andExpect(jsonPath("$.oidc").value(true)).andExpect(jsonPath("$.oidcUrl").value("/oauth2/authorization/keycloak"));
    }
    @Test void keycloakLinkRedirectsToTheRealm() throws Exception {
        mvc.perform(get("/oauth2/authorization/keycloak")).andExpect(status().is3xxRedirection())
            .andExpect(header().string("Location", startsWith("http://localhost:8081/realms/workspace/protocol/openid-connect/auth?")));
    }
    @Test void builtInFormLoginStillWorks() throws Exception {
        mvc.perform(post("/login").with(csrf()).param("username", "user").param("password", "Test-only-user-1234"))
            .andExpect(authenticated().withUsername("user")).andExpect(redirectedUrl("/"));
        mvc.perform(get("/api/session")).andExpect(status().isUnauthorized());
    }
    @Test void keycloakUsersGetTheSameGroupRules() throws Exception {
        var dev1 = oidcLogin().authorities(new SimpleGrantedAuthority("GROUP_dev1"));
        mvc.perform(get("/api/groups/dev1/details").with(dev1)).andExpect(status().isOk());
        mvc.perform(get("/api/groups/dev2/details").with(dev1)).andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/summary").with(dev1)).andExpect(status().isForbidden());
    }
    @Test void tokenClaimsBecomeGroupsAndAdminRole() {
        KeycloakProperties keycloak = new KeycloakProperties(true, "x", "x", "x", "groups", "roles", "admin");
        assertEquals(Set.of("GROUP_dev", "ROLE_ADMIN"),
            names(KeycloakAuthorityMapper.authorities(Map.of("groups", List.of("/dev"), "roles", List.of("admin", "offline_access")), keycloak)));
        assertEquals(Set.of("GROUP_dev1"),
            names(KeycloakAuthorityMapper.authorities(Map.of("groups", List.of("dev1", "Bad Name", "/a/b"), "roles", List.of("user")), keycloak)));
        assertEquals(Set.of(), names(KeycloakAuthorityMapper.authorities(Map.of("groups", "dev1"), keycloak)));
    }
    private static Set<String> names(Set<GrantedAuthority> authorities) {
        return authorities.stream().map(GrantedAuthority::getAuthority).collect(Collectors.toSet());
    }
}
