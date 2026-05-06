package com.example.demo.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.boot.info.BuildProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.lang.management.ManagementFactory;
import java.time.Instant;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/health")
@Tag(name = "Health", description = "Lightweight health endpoints — public + authenticated")
public class HealthController {

    @Autowired(required = false)
    private BuildProperties buildProperties;

    @GetMapping("/public")
    @Operation(summary = "Public status — anyone can call, returns minimal info")
    public Map<String, Object> publicStatus() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "UP");
        body.put("timestamp", Instant.now());
        return body;
    }

    @GetMapping("/secure")
    @Operation(summary = "Authenticated status — richer details for logged-in callers")
    public Map<String, Object> secureStatus(Authentication auth) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "UP");
        body.put("timestamp", Instant.now());
        body.put("user", auth.getName());
        body.put("authorities", auth.getAuthorities());
        body.put("jvmUptimeMs", ManagementFactory.getRuntimeMXBean().getUptime());
        body.put("startTime", new Date(ManagementFactory.getRuntimeMXBean().getStartTime()));
        body.put("javaVersion", System.getProperty("java.version"));
        body.put("processors", Runtime.getRuntime().availableProcessors());
        body.put("freeMemoryMb", Runtime.getRuntime().freeMemory() / (1024 * 1024));
        body.put("totalMemoryMb", Runtime.getRuntime().totalMemory() / (1024 * 1024));
        if (buildProperties != null) {
            body.put("appVersion", buildProperties.getVersion());
            body.put("appName", buildProperties.getName());
        }
        return body;
    }
}
