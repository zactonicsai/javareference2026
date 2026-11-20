package com.appsteam.launchpad.status;

import com.appsteam.launchpad.catalog.ServiceCatalog;
import com.appsteam.launchpad.catalog.ServiceDefinition;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Polls every service that has a {@code healthUrl} on a fixed schedule and keeps the last result in memory.
 * Results feed the launch page badges, {@code /status}, the "service unavailable" page and the
 * {@code launchpad_service_up} / {@code launchpad_service_latency_ms} Prometheus gauges.
 *
 * <p>Rules: any HTTP response &lt; 500 counts as UP (401/403 mean "alive but protected", 3xx "alive and
 * redirecting"); 5xx, connection errors and timeouts count as DOWN.
 */
@Service
public class StatusChecker {

    private static final Logger log = LoggerFactory.getLogger(StatusChecker.class);

    private final ServiceCatalog catalog;
    private final RestClient restClient;
    private final MeterRegistry meterRegistry;
    private final Map<String, ServiceStatus> statuses = new ConcurrentHashMap<>();

    public StatusChecker(ServiceCatalog catalog, RestClient statusRestClient, MeterRegistry meterRegistry) {
        this.catalog = catalog;
        this.restClient = statusRestClient;
        this.meterRegistry = meterRegistry;
    }

    @PostConstruct
    void registerGauges() {
        for (ServiceDefinition service : catalog.services()) {
            statuses.put(service.id(), ServiceStatus.unknown(service.id(),
                    service.isChecked() ? "not checked yet" : "no health URL configured"));
            Tags tags = Tags.of("service", service.id());
            Gauge.builder("launchpad.service.up", () -> statusOf(service.id()).isUp() ? 1 : 0)
                    .description("1 when the last health probe succeeded")
                    .tags(tags).register(meterRegistry);
            Gauge.builder("launchpad.service.latency.ms", () -> statusOf(service.id()).latencyMs())
                    .description("Round-trip time of the last health probe in milliseconds")
                    .tags(tags).register(meterRegistry);
        }
    }

    @Scheduled(initialDelayString = "3s", fixedDelayString = "${app.status.interval:15s}")
    public void checkAll() {
        for (ServiceDefinition service : catalog.services()) {
            if (!service.isChecked()) {
                continue;
            }
            ServiceStatus previous = statusOf(service.id());
            ServiceStatus current = probe(service);
            statuses.put(service.id(), current);
            if (previous.state() != current.state()) {
                log.info("service {} is now {} ({}, {} ms){}", service.id(), current.state(), current.httpStatus(),
                        current.latencyMs(), current.message().isBlank() ? "" : ": " + current.message());
            }
        }
    }

    /** Re-check a single service right now (used by the unavailable page's "try again"). */
    public ServiceStatus refresh(String id) {
        ServiceDefinition service = catalog.service(id);
        if (!service.isChecked()) {
            return statusOf(id);
        }
        ServiceStatus status = probe(service);
        statuses.put(id, status);
        return status;
    }

    public ServiceStatus statusOf(String id) {
        return statuses.getOrDefault(id, ServiceStatus.unknown(id, "unknown service"));
    }

    public List<ServiceStatus> all() {
        return statuses.values().stream().sorted(Comparator.comparing(ServiceStatus::id)).toList();
    }

    public long downCount() {
        return statuses.values().stream().filter(ServiceStatus::isDown).count();
    }

    ServiceStatus probe(ServiceDefinition service) {
        Instant start = Instant.now();
        try {
            HttpStatusCode code = restClient.get().uri(service.healthUrl())
                    .exchange((request, response) -> response.getStatusCode());
            long ms = Duration.between(start, Instant.now()).toMillis();
            boolean up = code.value() < 500;
            return new ServiceStatus(service.id(), up ? ServiceStatus.State.UP : ServiceStatus.State.DOWN,
                    code.value(), ms, Instant.now(), up ? "" : "HTTP " + code.value());
        } catch (RestClientResponseException e) {
            long ms = Duration.between(start, Instant.now()).toMillis();
            return new ServiceStatus(service.id(), ServiceStatus.State.DOWN, e.getStatusCode().value(), ms, Instant.now(),
                    "HTTP " + e.getStatusCode().value());
        } catch (ResourceAccessException e) {
            long ms = Duration.between(start, Instant.now()).toMillis();
            return new ServiceStatus(service.id(), ServiceStatus.State.DOWN, 0, ms, Instant.now(), rootMessage(e));
        } catch (RuntimeException e) {
            long ms = Duration.between(start, Instant.now()).toMillis();
            log.warn("health check of {} failed unexpectedly", service.id(), e);
            return new ServiceStatus(service.id(), ServiceStatus.State.DOWN, 0, ms, Instant.now(), rootMessage(e));
        }
    }

    private static String rootMessage(Throwable t) {
        Throwable root = t;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String msg = root.getMessage();
        if (msg == null || msg.isBlank()) {
            msg = root.getClass().getSimpleName();
        }
        return msg.length() > 120 ? msg.substring(0, 117) + "..." : msg;
    }
}
