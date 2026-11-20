package com.appsteam.launchpad.security;

import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * What the templates and access checks need to know about the caller. Built from the Spring Security
 * {@link Authentication}; never null — anonymous visitors get {@link #ANONYMOUS}.
 */
public record CurrentUser(
        boolean authenticated,
        String username,
        String displayName,
        String email,
        Set<String> groups,
        Set<String> roles,
        boolean admin) {

    public static final CurrentUser ANONYMOUS =
            new CurrentUser(false, "", "Guest", "", Set.of(), Set.of(), false);

    public static CurrentUser from(Authentication auth, String adminGroup) {
        if (auth == null || !auth.isAuthenticated() || auth instanceof AnonymousAuthenticationToken) {
            return ANONYMOUS;
        }
        Set<String> groups = new TreeSet<>();
        Set<String> roles = new TreeSet<>();
        String username = auth.getName();
        String displayName = username;
        String email = "";
        if (auth.getPrincipal() instanceof OidcUser oidc) {
            Map<String, Object> claims = oidc.getClaims();
            displayName = firstNonBlank(oidc.getFullName(), oidc.getPreferredUsername(), username);
            email = Objects.requireNonNullElse(oidc.getEmail(), "");
            addAll(groups, claims.get("groups"));
            addAll(roles, claims.get("roles"));
            if (claims.get("realm_access") instanceof Map<?, ?> realmAccess) {
                addAll(roles, realmAccess.get("roles"));
            }
        }
        // ROLE_x authorities come from KeycloakAuthoritiesMapper (groups + realm roles); treat them as roles too
        for (GrantedAuthority a : auth.getAuthorities()) {
            String name = a.getAuthority();
            if (name != null && name.startsWith("ROLE_")) {
                roles.add(name.substring("ROLE_".length()));
            }
        }
        boolean admin = groups.contains(adminGroup) || roles.contains(adminGroup);
        return new CurrentUser(true, username, displayName, email,
                Collections.unmodifiableSet(groups), Collections.unmodifiableSet(roles), admin);
    }

    public boolean isAuthenticated() {
        return authenticated;
    }

    public boolean isAdmin() {
        return admin;
    }

    public boolean inGroup(String group) {
        return group != null && (groups.contains(group) || roles.contains(group));
    }

    public List<String> groupList() {
        return List.copyOf(groups);
    }

    private static void addAll(Set<String> out, Object value) {
        if (value instanceof Iterable<?> items) {
            for (Object item : items) {
                String s = String.valueOf(item).trim();
                if (s.startsWith("/")) {
                    s = s.substring(1);
                }
                if (!s.isBlank()) {
                    out.add(s);
                }
            }
        } else if (value instanceof String s && !s.isBlank()) {
            out.add(s.startsWith("/") ? s.substring(1) : s);
        }
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return "";
    }
}
