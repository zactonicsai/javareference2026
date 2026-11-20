package com.appsteam.launchpad.web;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/** Tiny formatting helper for templates. */
public final class Clock {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'").withZone(ZoneOffset.UTC);

    private Clock() {
    }

    public static String now() {
        return STAMP.format(Instant.now());
    }

    public static String format(Instant instant) {
        return instant == null ? "-" : STAMP.format(instant);
    }
}
