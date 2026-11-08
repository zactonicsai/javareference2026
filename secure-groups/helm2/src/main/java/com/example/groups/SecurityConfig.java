package com.example.groups;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
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
    @Bean
    SecurityFilterChain security(HttpSecurity http) throws Exception {
        http.authorizeHttpRequests(auth -> auth
            .requestMatchers("/login.html", "/assets/**", "/api/csrf", "/error").permitAll()
            .requestMatchers("/api/groups/dev/**").hasAuthority("GROUP_dev")
            .requestMatchers("/api/groups/dev1/**").hasAuthority("GROUP_dev1")
            .requestMatchers("/api/groups/dev2/**").hasAuthority("GROUP_dev2")
            .requestMatchers("/api/admin/**").hasRole("ADMIN")
            .requestMatchers("/", "/index.html", "/api/session", "/api/linux/**").authenticated()
            .anyRequest().denyAll());
        // CSRF stays enabled with the default session-backed token repository.
        http.formLogin(login -> login.loginPage("/login.html").loginProcessingUrl("/login")
            .defaultSuccessUrl("/", true).failureUrl("/login.html?error").permitAll());
        http.logout(logout -> logout.logoutUrl("/logout").logoutSuccessUrl("/login.html?logout")
            .invalidateHttpSession(true).clearAuthentication(true).deleteCookies("JSESSIONID"));
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
}
