package com.example.groups;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class SessionController {
    @GetMapping("/csrf")
    Map<String, String> csrf(CsrfToken token) {
        return Map.of("token", token.getToken(), "headerName", token.getHeaderName(), "parameterName", token.getParameterName());
    }
    @GetMapping("/session")
    Map<String, Object> session(Authentication auth, HttpSession session) {
        List<String> groups = auth.getAuthorities().stream().map(Object::toString)
            .filter(a -> a.startsWith("GROUP_")).map(a -> a.substring(6)).sorted().toList();
        return Map.of("username", auth.getName(), "groups", groups,
            "admin", auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN")),
            "createdAt", Instant.ofEpochMilli(session.getCreationTime()).toString(),
            "idleTimeoutSeconds", session.getMaxInactiveInterval());
        // Never return the actual session ID or password hash.
    }
    @GetMapping("/groups/{group}/details")
    Map<String, String> details(@PathVariable String group) {
        return Map.of("title", "Workspace " + group, "message", switch(group) {
            case "dev" -> "Development administration: review builds and coordinate releases.";
            case "dev1" -> "Team dev1: maintain the application backlog and review feature work.";
            case "dev2" -> "Team dev2: review quality checks and prepare test reports.";
            default -> throw new IllegalArgumentException("Unknown group");
        });
    }
    @PostMapping("/groups/{group}/acknowledge")
    Map<String, String> acknowledge(@PathVariable String group, HttpSession session) {
        // Harmless session-scoped mutation demonstrates POST authorization + CSRF.
        String time = Instant.now().toString();
        session.setAttribute("acknowledged:" + group, time);
        return Map.of("message", "Workspace " + group + " acknowledged at " + time);
    }
    @GetMapping("/admin/summary")
    Map<String, Object> admin() {
        return Map.of("title", "Administration", "message", "Three fixed principals are configured. Admin belongs only to dev; dev1 and dev2 remain separate.", "userCount", 3);
    }
}
