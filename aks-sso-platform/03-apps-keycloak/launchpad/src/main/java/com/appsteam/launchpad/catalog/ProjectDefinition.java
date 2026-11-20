package com.appsteam.launchpad.catalog;

import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.List;

/**
 * A project page at /projects/{id}, visible only to members of {@code group} (and platform admins).
 */
public record ProjectDefinition(
        String id,
        String name,
        String group,
        @DefaultValue("") String description,
        @DefaultValue("") String owner,
        @DefaultValue List<Link> links) {

    public record Link(String name, String url, @DefaultValue("") String description) {
    }
}
