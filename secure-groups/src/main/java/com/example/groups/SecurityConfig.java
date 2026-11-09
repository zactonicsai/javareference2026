package com.example.groups;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.client.oidc.web.logout.OidcClientInitiatedLogoutSuccessHandler;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.oidc.user.OidcUserAuthority;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;

@Configuration
public class SecurityConfig {
    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }
    @Bean
    UserDetailsService users(Environment env, PasswordEncoder encoder) {
        // Fixed principals; passwords are supplied at startup, never committed.
        return new InMemoryUserDetailsManager(
            User.withUsername("admin").password(encoder.encode(password(env, "ADMIN_PASSWORD"))).authorities("GROUP_dev", "ROLE_ADMIN").build(),
            User.withUsername("user").password(encoder.encode(password(env, "USER_PASSWORD"))).authorities("GROUP_dev1").build(),
            User.withUsername("user2").password(encoder.encode(password(env, "USER2_PASSWORD"))).authorities("GROUP_dev2").build());
    }
    private String password(Environment env, String key) {
        String value = env.getRequiredProperty(key);
        if (value.length() < 14 || value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72)
            throw new IllegalArgumentException(key + " must have at least 14 characters and at most 72 UTF-8 bytes");
        return value;
    }
    /**
     * Keycloak as an extra way to sign in. Created only when keycloak.enabled=true (the "keycloak" profile);
     * without this bean the application behaves exactly as before.
     * The endpoints are Keycloak's fixed paths below the realm address, so start-up does not depend on Keycloak being reachable.
     */
    @Bean
    @ConditionalOnProperty(name = "keycloak.enabled", havingValue = "true")
    ClientRegistrationRepository keycloakClients(KeycloakProperties keycloak, Environment env) {
        for (String[] setting : new String[][]{{"keycloak.issuer-uri", keycloak.issuerUri()}, {"keycloak.client-id", keycloak.clientId()},
                {"keycloak.client-secret", keycloak.clientSecret()}})
            if (setting[1] == null || setting[1].isBlank())
                throw new IllegalStateException(setting[0] + " must be set when keycloak.enabled=true");
        // The browser returns from Keycloak through a cross-site redirect. A "strict" session cookie is not sent
        // on that request, so the sign-in could never complete; "lax" still blocks cross-site POSTs.
        if ("strict".equalsIgnoreCase(env.getProperty("server.servlet.session.cookie.same-site")))
            throw new IllegalStateException("Keycloak sign-in needs server.servlet.session.cookie.same-site=lax. "
                + "Start with the 'keycloak' profile, which sets it, or set SESSION_SAME_SITE=lax.");
        String issuer = keycloak.issuerUri().replaceAll("/+$", "");
        String endpoints = issuer + "/protocol/openid-connect";
        ClientRegistration registration = ClientRegistration.withRegistrationId("keycloak")
            .clientName("Keycloak")
            .clientId(keycloak.clientId())
            .clientSecret(keycloak.clientSecret())
            .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
            .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
            .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
            .scope("openid", "profile", "email")
            .issuerUri(issuer)
            .authorizationUri(endpoints + "/auth")
            .tokenUri(endpoints + "/token")
            .jwkSetUri(endpoints + "/certs")
            .userInfoUri(endpoints + "/userinfo")
            .userNameAttributeName("preferred_username")
            .providerConfigurationMetadata(Map.of("end_session_endpoint", endpoints + "/logout"))
            .build();
        return new InMemoryClientRegistrationRepository(registration);
    }

    @Bean
    SecurityFilterChain security(HttpSecurity http, ObjectProvider<ClientRegistrationRepository> clients,
                                 KeycloakProperties keycloak) throws Exception {
        ClientRegistrationRepository oidcClients = clients.getIfAvailable(); // null unless Keycloak sign-in is switched on
        http.authorizeHttpRequests(auth -> auth
            .requestMatchers("/login.html", "/assets/**", "/api/csrf", "/api/login-options", "/error").permitAll()
            .requestMatchers("/api/groups/dev/**").hasAuthority("GROUP_dev")
            .requestMatchers("/api/groups/dev1/**").hasAuthority("GROUP_dev1")
            .requestMatchers("/api/groups/dev2/**").hasAuthority("GROUP_dev2")
            .requestMatchers("/api/admin/**").hasRole("ADMIN")
            .requestMatchers("/", "/index.html", "/api/session", "/api/linux/**").authenticated()
            .anyRequest().denyAll());
        // CSRF stays enabled with the default session-backed token repository.
        http.formLogin(login -> login.loginPage("/login.html").loginProcessingUrl("/login")
            .defaultSuccessUrl("/", true).failureUrl("/login.html?error").permitAll());
        if (oidcClients == null) {
            http.logout(logout -> logout.logoutUrl("/logout").logoutSuccessUrl("/login.html?logout")
                .invalidateHttpSession(true).clearAuthentication(true).deleteCookies("JSESSIONID"));
        } else {
            // Same login page and landing page as the form; the page shows a "Sign in with Keycloak" link.
            http.oauth2Login(login -> login.loginPage("/login.html").defaultSuccessUrl("/", true).failureUrl("/login.html?error")
                .userInfoEndpoint(user -> user.userAuthoritiesMapper(authorities -> keycloakAuthorities(authorities, keycloak))));
            // Keycloak users are also signed out of Keycloak; built-in users just return to the login page.
            OidcClientInitiatedLogoutSuccessHandler signedOut = new OidcClientInitiatedLogoutSuccessHandler(oidcClients);
            signedOut.setPostLogoutRedirectUri("{baseUrl}/login.html?logout");
            signedOut.setDefaultTargetUrl("/login.html?logout");
            http.logout(logout -> logout.logoutUrl("/logout").logoutSuccessHandler(signedOut)
                .invalidateHttpSession(true).clearAuthentication(true).deleteCookies("JSESSIONID"));
        }
        http.sessionManagement(session -> session.sessionFixation(fixation -> fixation.changeSessionId()));
        http.requestCache(cache -> cache.disable());
        var apiMatcher = new AntPathRequestMatcher("/api/**");
        var loginEntryPoint = new LoginUrlAuthenticationEntryPoint("/login.html");
        // Choose by URL explicitly, independent of Accept headers or default ordering.
        http.exceptionHandling(errors -> errors.authenticationEntryPoint((request, response, exception) -> {
            if (apiMatcher.matches(request)) {
                response.sendError(401);
            } else {
                loginEntryPoint.commence(request, response, exception);
            }
        }));
        http.headers(headers -> headers
            .contentSecurityPolicy(csp -> csp.policyDirectives("default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self'; connect-src 'self'; object-src 'none'; base-uri 'none'; frame-ancestors 'none'; form-action 'self'"))
            .frameOptions(frame -> frame.deny())
            .referrerPolicy(ref -> ref.policy(org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER))
            .permissionsPolicy(policy -> policy.policy("camera=(), microphone=(), geolocation=()")));
        return http.build();
    }

    /** Replaces the authorities Spring derives from the token with GROUP_* / ROLE_ADMIN taken from Keycloak's claims. */
    private static Set<GrantedAuthority> keycloakAuthorities(java.util.Collection<? extends GrantedAuthority> granted, KeycloakProperties keycloak) {
        Set<GrantedAuthority> mapped = new LinkedHashSet<>();
        for (GrantedAuthority authority : granted)
            if (authority instanceof OidcUserAuthority oidc) {
                Map<String, Object> claims = new HashMap<>(oidc.getIdToken().getClaims());
                if (oidc.getUserInfo() != null) claims.putAll(oidc.getUserInfo().getClaims());
                mapped.addAll(KeycloakAuthorityMapper.authorities(claims, keycloak));
            }
        return mapped;
    }
}
