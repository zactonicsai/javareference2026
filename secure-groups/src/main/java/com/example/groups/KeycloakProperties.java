package com.example.groups;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Keycloak (OpenID Connect) sign-in settings, bound from the "keycloak" section of application.yml.
 * Nothing here is used unless enabled is true; the built-in username/password sign-in always stays available.
 */
@ConfigurationProperties("keycloak")
public record KeycloakProperties(
        @DefaultValue("false") boolean enabled,
        String issuerUri,
        String clientId,
        String clientSecret,
        @DefaultValue("groups") String groupsClaim,
        @DefaultValue("roles") String rolesClaim,
        @DefaultValue("admin") String adminRole) {}
