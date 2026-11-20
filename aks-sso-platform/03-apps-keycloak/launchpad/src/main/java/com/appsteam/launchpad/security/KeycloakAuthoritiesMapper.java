package com.appsteam.launchpad.security;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.authority.mapping.GrantedAuthoritiesMapper;
import org.springframework.security.oauth2.core.oidc.user.OidcUserAuthority;
import org.springframework.security.oauth2.core.user.OAuth2UserAuthority;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Turns Keycloak's {@code groups} claim (group membership mapper) and realm roles ({@code roles} claim or
 * {@code realm_access.roles}) into Spring {@code ROLE_<name>} authorities, so {@code hasRole("admin")} and
 * {@code hasRole("project2")} work out of the box.
 */
@Component
public class KeycloakAuthoritiesMapper implements GrantedAuthoritiesMapper {

    @Override
    public Collection<? extends GrantedAuthority> mapAuthorities(Collection<? extends GrantedAuthority> authorities) {
        Set<GrantedAuthority> mapped = new LinkedHashSet<>(authorities);
        for (GrantedAuthority authority : authorities) {
            Map<String, Object> claims = new HashMap<>();
            if (authority instanceof OidcUserAuthority oidc) {
                claims.putAll(oidc.getIdToken().getClaims());
                if (oidc.getUserInfo() != null) {
                    claims.putAll(oidc.getUserInfo().getClaims());
                }
            } else if (authority instanceof OAuth2UserAuthority oauth2) {
                claims.putAll(oauth2.getAttributes());
            }
            addRoles(mapped, claims.get("groups"));
            addRoles(mapped, claims.get("roles"));
            if (claims.get("realm_access") instanceof Map<?, ?> realmAccess) {
                addRoles(mapped, realmAccess.get("roles"));
            }
        }
        return mapped;
    }

    private static void addRoles(Set<GrantedAuthority> out, Object value) {
        if (!(value instanceof Iterable<?> items)) {
            return;
        }
        for (Object item : items) {
            String name = String.valueOf(item).trim();
            if (name.startsWith("/")) {
                name = name.substring(1);           // Keycloak group paths look like /project2
            }
            if (!name.isBlank() && !name.startsWith("default-roles-") && !name.equals("offline_access")
                    && !name.equals("uma_authorization")) {
                out.add(new SimpleGrantedAuthority("ROLE_" + name));
            }
        }
    }
}
