package com.appsteam.launchpad.catalog;

import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.List;

/**
 * One tile on the launch page.
 *
 * @param id        URL-safe identifier (used in /go/{id}, /unavailable/{id}, metrics)
 * @param name      display name
 * @param description one line shown on the tile
 * @param url       where the tile links to; "{publicUrl}" is replaced with the site's public URL
 * @param healthUrl what the status checker calls (ideally the in-cluster Service URL, plain http); empty = not checked
 * @param groups    Keycloak groups that may open it; empty = everyone (anonymous included)
 * @param icon      one of: globe, key, chart, folder, shield, bolt, book, cog (see layout.html icon sprite)
 * @param order     sort key (lower first)
 * @param external  open in a new tab
 */
public record ServiceDefinition(
        String id,
        String name,
        @DefaultValue("") String description,
        String url,
        @DefaultValue("") String healthUrl,
        @DefaultValue List<String> groups,
        @DefaultValue("globe") String icon,
        @DefaultValue("100") int order,
        @DefaultValue("false") boolean external) {

    public boolean isPublic() {
        return groups == null || groups.isEmpty();
    }

    public boolean isChecked() {
        return healthUrl != null && !healthUrl.isBlank();
    }

    public String resolvedUrl(String publicUrl) {
        return url == null ? "#" : url.replace("{publicUrl}", publicUrl);
    }
}
