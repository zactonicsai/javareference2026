package com.example.groups;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

/** The Linux environment: started and stopped with Helm, watched and used through kubectl. */
@Service
public class LinuxEnvironmentService {
    private static final String RELEASE_KEY = "linux";
    private static final Set<String> FAILURE_REASONS = Set.of("ImagePullBackOff", "ErrImagePull", "InvalidImageName",
        "CrashLoopBackOff", "CreateContainerConfigError", "CreateContainerError", "RunContainerError", "Unschedulable", "Error");
    private final HelmCommandService helm;
    private final KubectlCommandService kubectl;
    private final ObjectMapper mapper;

    public LinuxEnvironmentService(HelmCommandService helm, KubectlCommandService kubectl, ObjectMapper mapper) {
        this.helm = helm; this.kubectl = kubectl; this.mapper = mapper;
    }

    public record Step(String label, boolean done) {}
    public record Pod(String name, String phase, boolean ready, int restarts, String reason, String ip, String node, boolean terminating) {}
    public record ServiceInfo(String name, String type, String clusterIp, List<String> ports, List<String> externalAddresses) {}
    /** state is one of: stopped, starting, running, stopping, error. */
    public record Status(String state, String message, List<Step> steps, List<Pod> pods, ServiceInfo service, boolean exposed) {}

    public CliRunner.Result start() { return helm.startRelease(RELEASE_KEY); }

    public CliRunner.Result stop() { return helm.stopRelease(RELEASE_KEY); }

    public CliRunner.Result expose() {
        HelmProperties.Release release = helm.release(RELEASE_KEY);
        CliRunner.Result result = kubectl.exposeService(release.namespace(), release.releaseName());
        if (!result.ok()) throw new CliRunner.CliException("kubectl patch failed:\n" + result.output());
        return result;
    }

    public Status status() {
        HelmProperties.Release release = helm.release(RELEASE_KEY);
        CliRunner.Result result = kubectl.getPodsAndServices(release.namespace(), selector(release));
        if (!result.ok()) throw new CliRunner.CliException("kubectl get failed:\n" + result.output());
        try {
            return parseStatus(mapper.readTree(result.stdout()));
        } catch (JsonProcessingException e) {
            throw new CliRunner.CliException("kubectl returned output that is not JSON", e);
        }
    }

    public CliRunner.Result exec(String command) {
        return kubectl.exec(helm.release(RELEASE_KEY).namespace(), currentPod(), command);
    }

    public CliRunner.Result logs() {
        return kubectl.logs(helm.release(RELEASE_KEY).namespace(), currentPod());
    }

    public CliRunner.Result info() {
        HelmProperties.Release release = helm.release(RELEASE_KEY);
        return kubectl.describe(release.namespace(), selector(release));
    }

    private String selector(HelmProperties.Release release) {
        return "app.kubernetes.io/instance=" + release.releaseName();
    }

    /** The pod that exec and logs talk to: a ready one if possible, otherwise the newest still coming up. */
    private String currentPod() {
        return status().pods().stream().filter(pod -> !pod.terminating())
            .max(Comparator.comparing(Pod::ready)).map(Pod::name)
            .orElseThrow(() -> new CliRunner.NoPodException("No Linux pod exists yet. Press Start first."));
    }

    /** Turns "kubectl get pods,services -o json" into the progress summary shown in the browser. */
    static Status parseStatus(JsonNode root) {
        List<Pod> pods = new ArrayList<>();
        ServiceInfo service = null;
        for (JsonNode item : root.path("items")) {
            String kind = item.path("kind").asText();
            if (kind.equals("Pod")) pods.add(parsePod(item));
            else if (kind.equals("Service")) service = parseService(item);
        }
        List<Pod> live = pods.stream().filter(pod -> !pod.terminating()).toList();
        boolean scheduled = live.stream().anyMatch(pod -> !pod.node().isEmpty());
        boolean started = live.stream().anyMatch(pod -> pod.phase().equals("Running"));
        boolean ready = live.stream().anyMatch(Pod::ready);
        List<Step> steps = List.of(
            new Step("Release deployed", service != null || !live.isEmpty()),
            new Step("Service created", service != null),
            new Step("Pod scheduled", scheduled),
            new Step("Container started", started),
            new Step("Pod ready", ready));
        boolean exposed = service != null && !service.type().equals("ClusterIP");

        String state, message;
        if (ready) {
            state = "running";
            message = "Linux is running" + (exposed ? " and exposed as " + service.type() + "." : ".");
        } else if (!live.isEmpty()) {
            Pod pod = live.get(0);
            boolean failing = FAILURE_REASONS.contains(pod.reason());
            state = failing ? "error" : "starting";
            message = "Pod " + pod.name() + " is " + (pod.reason().isEmpty() ? pod.phase() : pod.reason())
                + (failing ? ". Kubernetes keeps retrying; see Info for details." : "...");
        } else if (!pods.isEmpty()) {
            state = "stopping";
            message = "Pod " + pods.get(0).name() + " is terminating...";
        } else if (service != null) {
            state = "starting";
            message = "Service created; waiting for the pod...";
        } else {
            state = "stopped";
            message = "Linux is not running. Press Start to deploy it.";
        }
        return new Status(state, message, steps, pods, service, exposed);
    }

    private static Pod parsePod(JsonNode item) {
        JsonNode metadata = item.path("metadata"), status = item.path("status");
        JsonNode containers = status.path("containerStatuses");
        boolean ready = containers.size() > 0;
        int restarts = 0;
        String reason = "";
        for (JsonNode container : containers) {
            ready &= container.path("ready").asBoolean(false);
            restarts += container.path("restartCount").asInt(0);
            if (reason.isEmpty()) reason = container.path("state").path("waiting").path("reason").asText("");
            if (reason.isEmpty()) reason = container.path("state").path("terminated").path("reason").asText("");
        }
        if (reason.isEmpty())
            for (JsonNode condition : status.path("conditions"))
                if (condition.path("type").asText().equals("PodScheduled") && condition.path("status").asText().equals("False"))
                    reason = condition.path("reason").asText("");
        return new Pod(metadata.path("name").asText(), status.path("phase").asText("Unknown"), ready, restarts, reason,
            status.path("podIP").asText(""), item.path("spec").path("nodeName").asText(""), metadata.hasNonNull("deletionTimestamp"));
    }

    private static ServiceInfo parseService(JsonNode item) {
        JsonNode spec = item.path("spec");
        List<String> ports = new ArrayList<>();
        for (JsonNode port : spec.path("ports")) {
            String text = port.path("port").asText() + " -> " + port.path("targetPort").asText();
            if (port.path("nodePort").asInt(0) > 0) text += " (node port " + port.path("nodePort").asInt() + ")";
            ports.add(text);
        }
        List<String> external = new ArrayList<>();
        for (JsonNode ingress : item.path("status").path("loadBalancer").path("ingress")) {
            String address = ingress.path("ip").asText(ingress.path("hostname").asText(""));
            if (!address.isEmpty()) external.add(address);
        }
        return new ServiceInfo(item.path("metadata").path("name").asText(), spec.path("type").asText("ClusterIP"),
            spec.path("clusterIP").asText(""), ports, external);
    }
}
