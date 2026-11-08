package com.example.groups;

import java.util.Map;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Linux environment actions for any signed-in user (POST also requires the CSRF token).
 * The release, namespace, chart and paths are fixed server-side; only the terminal command comes from the browser.
 */
@RestController
@RequestMapping("/api/linux")
public class LinuxController {
    private static final Logger log = LoggerFactory.getLogger(LinuxController.class);
    private static final int MAX_COMMAND_LENGTH = 2000;
    private final LinuxEnvironmentService linux;

    public LinuxController(LinuxEnvironmentService linux) { this.linux = linux; }

    @GetMapping("/status")
    ResponseEntity<?> status() { return guarded("Reading the status", linux::status); }

    @PostMapping("/start")
    ResponseEntity<?> start() { return guarded("Starting Linux", () -> body(linux.start())); }

    @PostMapping("/stop")
    ResponseEntity<?> stop() { return guarded("Stopping Linux", () -> body(linux.stop())); }

    @PostMapping("/expose")
    ResponseEntity<?> expose() { return guarded("Exposing Linux", () -> body(linux.expose())); }

    @GetMapping("/logs")
    ResponseEntity<?> logs() { return guarded("Reading the logs", () -> body(linux.logs())); }

    @GetMapping("/info")
    ResponseEntity<?> info() { return guarded("Reading the pod information", () -> body(linux.info())); }

    @PostMapping("/exec")
    ResponseEntity<?> exec(@RequestBody Map<String, String> request, java.security.Principal principal) {
        String command = request.get("command");
        if (command == null || command.isBlank() || command.length() > MAX_COMMAND_LENGTH)
            return ResponseEntity.badRequest().body(Map.of("message", "Enter a command of at most " + MAX_COMMAND_LENGTH + " characters."));
        log.info("Pod command requested by {}", principal.getName());
        return guarded("Running the command", () -> body(linux.exec(command)));
    }

    private static Map<String, Object> body(CliRunner.Result result) {
        return Map.of("command", result.command(), "output", result.output(), "exitCode", result.exitCode());
    }

    /** Tool failures are logged in full; the browser only gets a short message, in line with the app's error policy. */
    private ResponseEntity<?> guarded(String what, Supplier<Object> action) {
        try {
            return ResponseEntity.ok(action.get());
        } catch (CliRunner.NoPodException e) {
            return ResponseEntity.status(409).body(Map.of("message", e.getMessage()));
        } catch (CliRunner.CliTimeoutException e) {
            log.warn("{} timed out: {}", what, e.getMessage());
            return ResponseEntity.status(504).body(Map.of("message", what + " did not finish within the time limit and was cancelled."));
        } catch (CliRunner.CliException e) {
            log.error("{} failed", what, e);
            return ResponseEntity.status(502).body(Map.of("message", what + " failed. Check the server log for details."));
        }
    }
}
