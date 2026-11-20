package com.appsteam.launchpad.web;

import com.appsteam.launchpad.catalog.ServiceCatalog;
import com.appsteam.launchpad.catalog.ServiceDefinition;
import com.appsteam.launchpad.config.AppProperties;
import com.appsteam.launchpad.status.ServiceStatus;
import com.appsteam.launchpad.status.StatusChecker;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * JSON used by the page's live badges ({@code /js/status.js}) and handy for scripts/test.sh.
 * Deliberately public: it only says whether services answer, never who may use them.
 */
@RestController
public class StatusController {

    private final StatusChecker checker;
    private final ServiceCatalog catalog;
    private final AppProperties props;

    public StatusController(StatusChecker checker, ServiceCatalog catalog, AppProperties props) {
        this.checker = checker;
        this.catalog = catalog;
        this.props = props;
    }

    public record ServiceStatusView(String id, String name, String state, int httpStatus, long latencyMs,
                                    Instant checkedAt, String message) {
        static ServiceStatusView of(ServiceDefinition def, ServiceStatus s) {
            return new ServiceStatusView(def.id(), def.name(), s.state().name(), s.httpStatus(), s.latencyMs(),
                    s.checkedAt(), s.message());
        }
    }

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> status() {
        List<ServiceStatusView> services = catalog.services().stream()
                .map(def -> ServiceStatusView.of(def, checker.statusOf(def.id())))
                .toList();
        long down = services.stream().filter(s -> "DOWN".equals(s.state())).count();
        Map<String, Object> body = Map.of(
                "app", props.title(),
                "time", Instant.now(),
                "overall", down == 0 ? "UP" : "DEGRADED",
                "down", down,
                "services", services);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }

    @GetMapping("/status/{id}")
    public ResponseEntity<ServiceStatusView> status(@PathVariable String id) {
        ServiceDefinition def = catalog.service(id);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ServiceStatusView.of(def, checker.statusOf(id)));
    }
}
