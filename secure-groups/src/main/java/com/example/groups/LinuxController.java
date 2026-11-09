package com.example.groups;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Linux environments for signed-in users (POST also requires the CSRF token).
 * Every request is checked against the user's groups: dev1 members only see and control dev1-* environments.
 * The chart, namespace and image list are fixed server-side; the browser only picks from what the server offers.
 */
@RestController
@RequestMapping("/api/linux")
public class LinuxController {
    private static final Logger log = LoggerFactory.getLogger(LinuxController.class);
    private static final int MAX_COMMAND_LENGTH = 2000;
    private final LinuxEnvironmentService linux;

    public LinuxController(LinuxEnvironmentService linux) { this.linux = linux; }

    /** The static list of images a user may choose from. */
    @GetMapping("/images")
    List<LinuxEnvironmentService.ImageOption> images() { return linux.images(); }

    /** The environments that exist for the user's groups. */
    @GetMapping("/environments")
    ResponseEntity<?> list(Authentication auth) {
        return guarded("Listing the environments", () -> linux.list(groups(auth)));
    }

    /** JSON body: {"group", "name", "image", "sshPublicKey"}; all optional except where the user has several groups. */
    @PostMapping("/environments")
    ResponseEntity<?> start(@RequestBody(required = false) Map<String, String> body, Authentication auth) {
        Map<String, String> request = body == null ? Map.of() : body;
        Set<String> groups = groups(auth);
        return guarded("Starting the environment", () -> {
            String group = request.getOrDefault("group", groups.size() == 1 ? groups.iterator().next() : null);
            String name = request.getOrDefault("name", "linux");
            String image = request.getOrDefault("image", linux.images().isEmpty() ? null : linux.images().get(0).id());
            String pasted = request.get("sshPublicKey");
            String keys = pasted == null || pasted.isBlank() ? null : LinuxEnvironmentService.validatePublicKeys(pasted);
            log.info("Environment {}-{} (image {}) requested by {}", group, name, image, auth.getName());
            Map<String, Object> result = body(linux.start(groups, group, name, image, keys));
            result.put("environment", group + "-" + name);
            return result;
        });
    }

    @GetMapping("/environments/{environment}/status")
    ResponseEntity<?> status(@PathVariable("environment") String environment, Authentication auth) {
        return guarded("Reading the status", () -> linux.status(linux.resolve(groups(auth), environment)));
    }

    @PostMapping("/environments/{environment}/stop")
    ResponseEntity<?> stop(@PathVariable("environment") String environment, Authentication auth) {
        return guarded("Stopping the environment", () -> body(linux.stop(linux.resolve(groups(auth), environment))));
    }

    @PostMapping("/environments/{environment}/expose")
    ResponseEntity<?> expose(@PathVariable("environment") String environment, Authentication auth) {
        return guarded("Exposing the environment", () -> body(linux.expose(linux.resolve(groups(auth), environment))));
    }

    @GetMapping("/environments/{environment}/logs")
    ResponseEntity<?> logs(@PathVariable("environment") String environment, Authentication auth) {
        return guarded("Reading the logs", () -> body(linux.logs(linux.resolve(groups(auth), environment))));
    }

    @GetMapping("/environments/{environment}/info")
    ResponseEntity<?> info(@PathVariable("environment") String environment, Authentication auth) {
        return guarded("Reading the pod information", () -> body(linux.info(linux.resolve(groups(auth), environment))));
    }

    @PostMapping("/environments/{environment}/exec")
    ResponseEntity<?> exec(@PathVariable("environment") String environment, @RequestBody Map<String, String> request, Authentication auth) {
        String command = request.get("command");
        if (command == null || command.isBlank() || command.length() > MAX_COMMAND_LENGTH)
            return ResponseEntity.badRequest().body(Map.of("message", "Enter a command of at most " + MAX_COMMAND_LENGTH + " characters."));
        return guarded("Running the command", () -> {
            LinuxEnvironmentService.Environment target = linux.resolve(groups(auth), environment);
            log.info("Command in {} requested by {}", target.name(), auth.getName());
            return body(linux.exec(target, command));
        });
    }

    /** The user's groups, taken from the GROUP_* authorities granted at sign-in. */
    private static Set<String> groups(Authentication auth) {
        return auth.getAuthorities().stream().map(GrantedAuthority::getAuthority)
            .filter(authority -> authority.startsWith("GROUP_")).map(authority -> authority.substring(6))
            .collect(Collectors.toCollection(TreeSet::new));
    }

    private static Map<String, Object> body(CliRunner.Result result) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("command", result.command());
        body.put("output", result.output());
        body.put("exitCode", result.exitCode());
        return body;
    }

    /** Tool failures are logged in full; the browser only gets a short message, in line with the app's error policy. */
    private ResponseEntity<?> guarded(String what, Supplier<Object> action) {
        try {
            return ResponseEntity.ok(action.get());
        } catch (LinuxEnvironmentService.RequestException e) {
            return ResponseEntity.status(e.status()).body(Map.of("message", e.getMessage()));
        } catch (CliRunner.CliTimeoutException e) {
            log.warn("{} timed out: {}", what, e.getMessage());
            return ResponseEntity.status(504).body(Map.of("message", what + " did not finish within the time limit and was cancelled."));
        } catch (CliRunner.CliException e) {
            log.error("{} failed", what, e);
            return ResponseEntity.status(502).body(Map.of("message", what + " failed. Check the server log for details."));
        }
    }
}
