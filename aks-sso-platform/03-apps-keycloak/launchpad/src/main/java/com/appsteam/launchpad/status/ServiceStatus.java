package com.appsteam.launchpad.status;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * Last known state of one service.
 *
 * @param state     UP, DOWN or UNKNOWN (not checked yet / no healthUrl)
 * @param httpStatus HTTP status of the last probe, 0 when the connection failed
 * @param latencyMs round-trip time of the last probe
 * @param checkedAt when the probe ran (null if never)
 * @param message   short human-readable reason, e.g. "Connection refused"
 */
public record ServiceStatus(String id, State state, int httpStatus, long latencyMs, Instant checkedAt, String message) {

    public enum State { UP, DOWN, UNKNOWN }

    public static ServiceStatus unknown(String id, String message) {
        return new ServiceStatus(id, State.UNKNOWN, 0, 0, null, message);
    }

    public boolean isUp() {
        return state == State.UP;
    }

    public boolean isDown() {
        return state == State.DOWN;
    }

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss 'UTC'").withZone(ZoneOffset.UTC);

    /** "12:30:05 UTC" or "never" — for templates (Thymeleaf cannot format a zone-less Instant). */
    public String checkedAtText() {
        return checkedAt == null ? "never" : TIME.format(checkedAt);
    }

    /** "12:30:05 UTC (14 s ago)" */
    public String checkedText() {
        long ago = secondsAgo();
        return ago < 0 ? "never" : checkedAtText() + " (" + ago + " s ago)";
    }

    public long secondsAgo() {
        return checkedAt == null ? -1 : Duration.between(checkedAt, Instant.now()).toSeconds();
    }

    /** CSS modifier used by the templates and status.js: up | down | unknown */
    public String css() {
        return state.name().toLowerCase();
    }
}
