package com.appsteam.launchpad.web;

import java.util.List;

/** Signed in, but not in the right group → 403 page that names the group. */
public class ForbiddenException extends RuntimeException {

    private final List<String> requiredGroups;

    public ForbiddenException(String message, List<String> requiredGroups) {
        super(message);
        this.requiredGroups = requiredGroups == null ? List.of() : List.copyOf(requiredGroups);
    }

    public List<String> getRequiredGroups() {
        return requiredGroups;
    }
}
