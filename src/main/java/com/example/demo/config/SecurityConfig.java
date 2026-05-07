package com.example.demo.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.servlet.HandlerExceptionResolver;

import java.util.List;

/**
 * Static, in-memory security with three roles: ADMIN, MANAGER, USER.
 * HTTP Basic auth (suitable for demos / curl / fetch-with-credentials).
 *
 * Best practices applied:
 *  - Stateless session (REST API style)
 *  - CSRF disabled only because we use stateless basic auth
 *  - BCrypt password encoder
 *  - Method-level security enabled (@PreAuthorize available)
 *  - Default deny: every endpoint authorized explicitly
 *  - 401/403 are delegated to the global @RestControllerAdvice via
 *    HandlerExceptionResolver, so all errors share one ErrorResponse shape
 */
@Slf4j
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public InMemoryUserDetailsManager userDetailsService(PasswordEncoder encoder) {
        UserDetails admin = User.builder()
                .username("admin")
                .password(encoder.encode("admin123"))
                .roles("ADMIN", "MANAGER", "USER")
                .build();

        UserDetails manager = User.builder()
                .username("manager")
                .password(encoder.encode("manager123"))
                .roles("MANAGER", "USER")
                .build();

        UserDetails user = User.builder()
                .username("user")
                .password(encoder.encode("user123"))
                .roles("USER")
                .build();

        log.info("Bootstrapping in-memory users: admin/admin123, manager/manager123, user/user123");
        return new InMemoryUserDetailsManager(admin, manager, user);
    }

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            @Qualifier("handlerExceptionResolver") HandlerExceptionResolver resolver
    ) throws Exception {

        // Delegate Spring Security 401/403 to the @RestControllerAdvice
        AuthenticationEntryPoint authEntry = (req, res, ex) -> resolver.resolveException(req, res, null, ex);
        AccessDeniedHandler accessDenied   = (req, res, ex) -> resolver.resolveException(req, res, null, ex);

        http
            .csrf(csrf -> csrf.disable())
            .cors(Customizer.withDefaults())
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                // Public endpoints
                .requestMatchers(
                        "/", "/index.html", "/favicon.ico",
                        "/css/**", "/js/**", "/images/**",
                        "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html",
                        "/h2-console/**"
                ).permitAll()
                // Public actuator info
                .requestMatchers("/actuator/health", "/actuator/info").permitAll()
                // Privileged actuator endpoints
                .requestMatchers("/actuator/**").hasRole("ADMIN")
                // Role demo: any authenticated user — payload differs by role
                .requestMatchers(HttpMethod.GET, "/api/role/me").authenticated()
                // CRUD: read for any authenticated; write requires MANAGER+; delete only ADMIN
                .requestMatchers(HttpMethod.GET, "/api/products/**").hasAnyRole("USER", "MANAGER", "ADMIN")
                .requestMatchers(HttpMethod.POST, "/api/products/**").hasAnyRole("MANAGER", "ADMIN")
                .requestMatchers(HttpMethod.PUT, "/api/products/**").hasAnyRole("MANAGER", "ADMIN")
                .requestMatchers(HttpMethod.DELETE, "/api/products/**").hasRole("ADMIN")
                // Same rules for the Temporal/Postgres CRUD
                .requestMatchers(HttpMethod.GET, "/api/products-temporal/**").hasAnyRole("USER", "MANAGER", "ADMIN")
                .requestMatchers(HttpMethod.POST, "/api/products-temporal/**").hasAnyRole("MANAGER", "ADMIN")
                .requestMatchers(HttpMethod.PUT, "/api/products-temporal/**").hasAnyRole("MANAGER", "ADMIN")
                .requestMatchers(HttpMethod.DELETE, "/api/products-temporal/**").hasRole("ADMIN")
                // File upload — any authenticated user can upload/read; only ADMIN can delete
                .requestMatchers(HttpMethod.GET, "/api/files/**").hasAnyRole("USER", "MANAGER", "ADMIN")
                .requestMatchers(HttpMethod.POST, "/api/files/**").hasAnyRole("USER", "MANAGER", "ADMIN")
                .requestMatchers(HttpMethod.DELETE, "/api/files/**").hasRole("ADMIN")
                // Health controller
                .requestMatchers("/api/health/public").permitAll()
                .requestMatchers("/api/health/secure").authenticated()
                .anyRequest().authenticated()
            )
            .httpBasic(Customizer.withDefaults())
            .exceptionHandling(eh -> eh
                    .authenticationEntryPoint(authEntry)
                    .accessDeniedHandler(accessDenied))
            // Allow H2 console frames
            .headers(h -> h.frameOptions(f -> f.sameOrigin()));

        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration cfg = new CorsConfiguration();
        cfg.setAllowedOriginPatterns(List.of("*"));
        cfg.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        cfg.setAllowedHeaders(List.of("*"));
        cfg.setAllowCredentials(true);
        UrlBasedCorsConfigurationSource src = new UrlBasedCorsConfigurationSource();
        src.registerCorsConfiguration("/**", cfg);
        return src;
    }
}
