package com.appsteam.launchpad.config;

import com.appsteam.launchpad.security.KeycloakAuthoritiesMapper;
import com.appsteam.launchpad.security.KeycloakLogoutHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.client.oidc.authentication.OidcIdTokenDecoderFactory;
import org.springframework.security.oauth2.client.oidc.authentication.OidcIdTokenValidator;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestCustomizers;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestRedirectFilter;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.JwtDecoderFactory;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Access rules:
 * <pre>
 *   /, /status, /unavailable/**, static files, actuator (mgmt port) anyone
 *   /admin/**                                                     ROLE_&lt;adminGroup&gt;  (Keycloak group "admin")
 *   everything else (/restricted, /projects/**, /go/**)           any signed-in user; project pages check the group themselves
 * </pre>
 * Login is OIDC authorization-code with PKCE against Keycloak; there is no local login page — unauthenticated
 * requests go straight to Keycloak.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    public static final String REGISTRATION_ID = "keycloak";

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http,
                                            AppProperties props,
                                            KeycloakAuthoritiesMapper authoritiesMapper,
                                            ClientRegistrationRepository registrations) throws Exception {
        http
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/", "/index", "/status", "/status/**", "/unavailable/**",
                                "/css/**", "/js/**", "/img/**", "/favicon.svg", "/favicon.ico", "/robots.txt",
                                "/error", "/error/**", "/login/**", "/oauth2/**").permitAll()
                        // actuator lives on the management port (8081), which the gateway never routes
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/prometheus", "/actuator/info").permitAll()
                        .requestMatchers("/admin", "/admin/**").hasRole(props.adminGroup())
                        .anyRequest().authenticated())
                .oauth2Login(oauth2 -> oauth2
                        .loginPage("/oauth2/authorization/" + REGISTRATION_ID)   // no intermediate login page
                        .authorizationEndpoint(endpoint -> endpoint.authorizationRequestResolver(pkceResolver(registrations)))
                        .userInfoEndpoint(userInfo -> userInfo.userAuthoritiesMapper(authoritiesMapper)))
                .logout(logout -> logout
                        .logoutUrl("/logout")
                        .logoutSuccessHandler(new KeycloakLogoutHandler(props, registrations))
                        .invalidateHttpSession(true)
                        .clearAuthentication(true)
                        .deleteCookies("JSESSIONID"))
                .headers(headers -> headers
                        .contentSecurityPolicy(csp -> csp.policyDirectives(
                                "default-src 'self'; img-src 'self' data:; style-src 'self'; script-src 'self'; "
                                        + "font-src 'self'; connect-src 'self'; frame-ancestors 'none'; form-action 'self' "
                                        + props.keycloak().publicUrl()))
                        .referrerPolicy(rp -> rp.policy(
                                org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN)));
        return http.build();
    }

    /** Authorization-code flow with PKCE (S256) even though the client is confidential — the Keycloak client requires it. */
    private static OAuth2AuthorizationRequestResolver pkceResolver(ClientRegistrationRepository registrations) {
        DefaultOAuth2AuthorizationRequestResolver resolver = new DefaultOAuth2AuthorizationRequestResolver(
                registrations, OAuth2AuthorizationRequestRedirectFilter.DEFAULT_AUTHORIZATION_REQUEST_BASE_URI);
        resolver.setAuthorizationRequestCustomizer(OAuth2AuthorizationRequestCustomizers.withPkce());
        return resolver;
    }

    /**
     * ID tokens are fetched from the in-cluster token endpoint but carry the public issuer; without issuer-uri
     * (we avoid OIDC discovery over the self-signed public URL) Spring would not check {@code iss} at all.
     * This factory adds that check on top of the standard OIDC validation.
     */
    @Bean
    JwtDecoderFactory<ClientRegistration> idTokenDecoderFactory(AppProperties props) {
        OidcIdTokenDecoderFactory factory = new OidcIdTokenDecoderFactory();
        factory.setJwtValidatorFactory(registration -> new DelegatingOAuth2TokenValidator<>(
                new OidcIdTokenValidator(registration),
                new JwtIssuerValidator(props.keycloak().issuer())));
        return factory;
    }
}
