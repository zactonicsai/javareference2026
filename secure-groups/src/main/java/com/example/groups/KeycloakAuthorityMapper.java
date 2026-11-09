package com.example.groups;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/**
 * Turns the claims Keycloak puts in the ID token into this application's authorities, so a Keycloak
 * user gets exactly the same rights as a built-in user: group "dev1" becomes GROUP_dev1, and the
 * realm role "admin" becomes ROLE_ADMIN. Anything else in the token grants nothing.
 */
final class KeycloakAuthorityMapper {
    // Same shape the rest of the application accepts for group names.
    private static final Pattern GROUP = Pattern.compile("[a-z0-9]{1,20}");

    private KeycloakAuthorityMapper() { }

    static Set<GrantedAuthority> authorities(Map<String, Object> claims, KeycloakProperties keycloak) {
        Set<GrantedAuthority> authorities = new LinkedHashSet<>();
        if (claims.get(keycloak.groupsClaim()) instanceof Collection<?> groups)
            for (Object value : groups) {
                // Keycloak can send full paths such as "/dev1"; only top-level groups are used.
                String group = String.valueOf(value);
                if (group.startsWith("/")) group = group.substring(1);
                if (GROUP.matcher(group).matches()) authorities.add(new SimpleGrantedAuthority("GROUP_" + group));
            }
        if (claims.get(keycloak.rolesClaim()) instanceof Collection<?> roles && roles.contains(keycloak.adminRole()))
            authorities.add(new SimpleGrantedAuthority("ROLE_ADMIN"));
        return authorities;
    }
}
