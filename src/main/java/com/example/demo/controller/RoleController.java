package com.example.demo.controller;

import com.example.demo.dto.AdminDto;
import com.example.demo.dto.ManagerDto;
import com.example.demo.dto.PersonDto;
import com.example.demo.dto.UserDto;
import com.example.demo.exception.RoleException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@RestController
@RequestMapping("/api/role")
@Profile("!worker")
@RequiredArgsConstructor
@Tag(name = "Role", description = "Returns different DTOs depending on the caller's role")
public class RoleController {

    @GetMapping("/me")
    @Operation(summary = "Returns role-specific data for the authenticated caller")
    public ResponseEntity<Object> me(Authentication auth) {
        Set<String> roles = auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toSet());

        log.debug("Role lookup for user={} roles={}", auth.getName(), roles);

        if (roles.contains("ROLE_ADMIN")) {
            return ResponseEntity.ok(buildAdminDto(auth.getName()));
        }
        if (roles.contains("ROLE_MANAGER")) {
            return ResponseEntity.ok(buildManagerDto(auth.getName()));
        }
        if (roles.contains("ROLE_USER")) {
            return ResponseEntity.ok(buildUserDto(auth.getName()));
        }
        throw new RoleException.UnknownRoleException(auth.getName());
    }

    private AdminDto buildAdminDto(String username) {
        return AdminDto.builder()
                .role("ADMIN")
                .username(username)
                .issuedAt(Instant.now())
                .permissions(List.of("USER_MANAGE", "PRODUCT_DELETE", "AUDIT_READ", "ACTUATOR_FULL"))
                .systemMetrics(Map.of(
                        "activeSessions", 42,
                        "uptimeMinutes", 1440,
                        "totalProducts", 100,
                        "errorRate", 0.001
                ))
                .message("Welcome, system administrator. You have full control.")
                .build();
    }

    private ManagerDto buildManagerDto(String username) {
        PersonDto person = PersonDto.builder()
                .fullName("Maria Manager")
                .email(username + "@example.com")
                .phone("+1-555-0150")
                .department("Operations")
                .build();

        return ManagerDto.builder()
                .role("MANAGER")
                .person(person)
                .teamName("Sales North-East")
                .directReports(List.of("Alice", "Bob", "Carlos", "Diana"))
                .budgetUsd(150_000.00)
                .message("Hello manager. Here is your team overview.")
                .build();
    }

    private UserDto buildUserDto(String username) {
        return UserDto.builder()
                .role("USER")
                .username(username)
                .displayName("Standard User")
                .visibleItems(List.of("Dashboard", "My Profile", "Help Center"))
                .message("Welcome, " + username + "!")
                .build();
    }
}
