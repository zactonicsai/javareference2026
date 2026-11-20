package com.appsteam.launchpad;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Access rules end to end through the real security filter chain (no Keycloak needed: the OIDC client is
 * configured with explicit endpoints, so nothing is fetched at startup).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class LaunchpadSmokeTest {

    @Autowired
    MockMvc mvc;

    @Test
    void launchPageIsPublic() throws Exception {
        mvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Open tool")))
                .andExpect(header().string("Content-Security-Policy", containsString("default-src 'self'")));
    }

    @Test
    void statusJsonIsPublic() throws Exception {
        mvc.perform(get("/status"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("\"overall\"")));
    }

    @Test
    void restrictedPageRedirectsAnonymousToKeycloak() throws Exception {
        mvc.perform(get("/restricted"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("**/oauth2/authorization/keycloak"));
    }

    @Test
    @WithMockUser(username = "alice", roles = "project2")
    void restrictedPageOpensForSignedInUser() throws Exception {
        mvc.perform(get("/restricted"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("alice")));
    }

    @Test
    @WithMockUser(username = "alice", roles = "project2")
    void projectPageNeedsTheGroup() throws Exception {
        mvc.perform(get("/projects/project2")).andExpect(status().isOk());
        mvc.perform(get("/projects/project3")).andExpect(status().isForbidden());
        mvc.perform(get("/projects/nope")).andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(username = "ada", roles = "admin")
    void adminOpensEveryProjectAndTheAdminPage() throws Exception {
        mvc.perform(get("/projects/project3")).andExpect(status().isOk());
        mvc.perform(get("/admin")).andExpect(status().isOk());
    }

    @Test
    @WithMockUser(username = "bob", roles = "project3")
    void adminPageIsForbiddenForOthers() throws Exception {
        mvc.perform(get("/admin")).andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "alice", roles = "project2")
    void goRedirectsToTheServiceOrTheUnavailablePage() throws Exception {
        // p2-tool has no health URL -> UNKNOWN -> treated as reachable
        mvc.perform(get("/go/p2-tool")).andExpect(status().is3xxRedirection()).andExpect(header().string("Location", "http://p2.example"));
        mvc.perform(get("/unavailable/p2-tool")).andExpect(status().isOk());
    }

    @Test
    @WithMockUser(username = "bob", roles = "project3")
    void lockedServiceIsForbiddenForWrongGroup() throws Exception {
        mvc.perform(get("/go/p2-tool")).andExpect(status().isForbidden());
    }
}
