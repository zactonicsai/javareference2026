package com.appsteam.launchpad.config;

import com.appsteam.launchpad.catalog.ProjectDefinition;
import com.appsteam.launchpad.catalog.ServiceDefinition;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.List;

/**
 * Everything the chart can configure, bound from {@code app.*} (application.yaml + the mounted services.yaml).
 */
@Validated
@ConfigurationProperties(prefix = "app")
public record AppProperties(
        @DefaultValue("Apps Team Launchpad") String title,
        @DefaultValue("Single sign-on launch page for the team's services") String tagline,
        /** Public base URL of this site (what browsers use), e.g. https://aks-appsteam-x.centralus.cloudapp.azure.com */
        @NotBlank String publicUrl,
        /** Keycloak group whose members may open /admin and every project page. */
        @DefaultValue("admin") String adminGroup,
        @NotNull @Valid Keycloak keycloak,
        @Valid @DefaultValue Status status,
        @DefaultValue List<ProjectDefinition> projects,
        @DefaultValue List<ServiceDefinition> services,
        /** Services appended by scripts/add-service.sh (kept separate so chart defaults survive upgrades). */
        @DefaultValue List<ServiceDefinition> extraServices) {

    public record Keycloak(
            /** Browser-facing Keycloak base incl. path, e.g. https://host/auth */
            @NotBlank String publicUrl,
            /** In-cluster Keycloak base incl. path, e.g. http://keycloak.appsteam.svc.cluster.local:8080/auth */
            @NotBlank String internalUrl,
            @DefaultValue("appsteam") String realm) {

        public String issuer() {
            return publicUrl + "/realms/" + realm;
        }

        public String accountUrl() {
            return issuer() + "/account/";
        }

        public String adminConsoleUrl() {
            return publicUrl + "/admin/" + realm + "/console/";
        }

        public String endSessionUrl() {
            return issuer() + "/protocol/openid-connect/logout";
        }
    }

    public record Status(
            @DefaultValue("15s") Duration interval,
            @DefaultValue("2s") Duration connectTimeout,
            @DefaultValue("3s") Duration readTimeout) {
    }

    /** Chart defaults + additions, in display order. */
    public List<ServiceDefinition> allServices() {
        return java.util.stream.Stream.concat(services.stream(), extraServices.stream())
                .sorted(java.util.Comparator.comparingInt(ServiceDefinition::order).thenComparing(ServiceDefinition::name))
                .toList();
    }
}
