package com.example.demo.advice;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.util.UUID;

/**
 * Audit filter — logs every request with:
 *  - traceId (also placed in MDC so all logs in the request share it)
 *  - authenticated principal + roles
 *  - HTTP method + URI + status
 *  - latency in millis
 *  - client ip + user-agent
 *
 * Runs after Spring Security so the principal is populated.
 */
@Slf4j
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
public class AuditFilter extends OncePerRequestFilter {

    private static final String TRACE_ID = "traceId";
    private static final String USER_KEY = "user";

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {

        String traceId = req.getHeader("X-Trace-Id");
        if (traceId == null || traceId.isBlank()) {
            traceId = UUID.randomUUID().toString();
        }
        MDC.put(TRACE_ID, traceId);
        res.setHeader("X-Trace-Id", traceId);

        long start = System.currentTimeMillis();
        try {
            chain.doFilter(req, res);
        } finally {
            long elapsed = System.currentTimeMillis() - start;
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            String user = (auth == null || !auth.isAuthenticated()) ? "anonymous" : auth.getName();
            String roles = (auth == null) ? "-" : auth.getAuthorities().toString();
            MDC.put(USER_KEY, user);

            log.info("AUDIT ts={} traceId={} user={} roles={} method={} uri={} query={} status={} elapsedMs={} ip={} ua=\"{}\"",
                    Instant.now(),
                    traceId,
                    user,
                    roles,
                    req.getMethod(),
                    req.getRequestURI(),
                    req.getQueryString() == null ? "" : req.getQueryString(),
                    res.getStatus(),
                    elapsed,
                    extractIp(req),
                    req.getHeader("User-Agent"));

            MDC.clear();
        }
    }

    private String extractIp(HttpServletRequest req) {
        String fwd = req.getHeader("X-Forwarded-For");
        return (fwd != null && !fwd.isBlank()) ? fwd.split(",")[0].trim() : req.getRemoteAddr();
    }
}
