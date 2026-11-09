package com.example.groups;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.zip.CRC32;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

/**
 * Linux environments: each one is a Helm release named "<group>-<name>" (for example dev1-linux),
 * started and stopped with Helm, and watched and used through kubectl. A user only reaches
 * environments whose group is one of their own groups.
 */
@Service
public class LinuxEnvironmentService {
    private static final String CHART_KEY = "linux";
    private static final String GROUP_LABEL = "workspace-group", IMAGE_LABEL = "workspace-image", INSTANCE_LABEL = "app.kubernetes.io/instance";
    private static final Set<String> FAILURE_REASONS = Set.of("ImagePullBackOff", "ErrImagePull", "InvalidImageName",
        "CrashLoopBackOff", "CreateContainerConfigError", "CreateContainerError", "RunContainerError", "Unschedulable", "Error");
    // Names end up in Kubernetes object names and on command lines, so they are restricted to lowercase letters, digits and dashes.
    private static final Pattern GROUP = Pattern.compile("[a-z0-9]{1,20}");
    private static final Pattern NAME = Pattern.compile("[a-z0-9]([a-z0-9-]{0,18}[a-z0-9])?");
    private static final Pattern IMAGE_ID = Pattern.compile("[a-z0-9]([a-z0-9-]{0,30}[a-z0-9])?");
    private static final Pattern IMAGE_REPOSITORY = Pattern.compile("[a-z0-9][a-z0-9./_:-]{0,200}");
    private static final Pattern IMAGE_TAG = Pattern.compile("[A-Za-z0-9_][A-Za-z0-9._-]{0,127}");
    private static final Pattern PACKAGES = Pattern.compile("[a-z0-9.+ -]{0,1000}");
    private static final int MAX_KEYS = 10, MAX_KEY_LENGTH = 8192;
    // "<type> <base64> [comment]" only: no leading options such as command="..." and no control characters.
    private static final Pattern PUBLIC_KEY = Pattern.compile(
        "(ssh-ed25519|ssh-rsa|ecdsa-sha2-nistp(256|384|521)|sk-ssh-ed25519@openssh\\.com|sk-ecdsa-sha2-nistp256@openssh\\.com)"
        + " [A-Za-z0-9+/]{40,}={0,3}( [\\x20-\\x7E]{0,200})?");

    private final LinuxProperties props;
    private final HelmCommandService helm;
    private final KubectlCommandService kubectl;
    private final ObjectMapper mapper;

    public LinuxEnvironmentService(LinuxProperties props, HelmCommandService helm, KubectlCommandService kubectl, ObjectMapper mapper) {
        this.props = props; this.helm = helm; this.kubectl = kubectl; this.mapper = mapper;
    }

    /** A problem the user can fix; the message is shown in the browser with the given HTTP status. */
    public static class RequestException extends RuntimeException {
        private final int status;
        public RequestException(int status, String message) { super(message); this.status = status; }
        public int status() { return status; }
    }

    /** An environment the current user is allowed to use. */
    public record Environment(String group, String name) {}
    public record ImageOption(String id, String label, String description, String image) {}
    public record Summary(String name, String group, String image, String state) {}
    public record Step(String label, boolean done) {}
    public record Pod(String name, String phase, boolean ready, int restarts, String reason, String ip, String node, boolean terminating) {}
    public record ServiceInfo(String name, String type, String clusterIp, List<String> ports, List<String> externalAddresses) {}
    public record VirtualServiceInfo(String name, List<String> hosts, List<String> gateways, List<String> routes) {}
    /** state is one of: stopped, starting, running, stopping, error. sshAccess lists ready-to-copy ssh commands. */
    public record Status(String name, String group, String image, String state, String message, List<Step> steps, List<Pod> pods,
                         ServiceInfo service, boolean exposed, List<VirtualServiceInfo> virtualServices, List<String> sshAccess) {}

    /** The static image list from the configuration, as shown in the browser. */
    public List<ImageOption> images() {
        return props.images().stream()
            .map(image -> new ImageOption(image.id(), image.label(), image.description(), image.repository() + ":" + image.tag())).toList();
    }

    /** Checks that the environment belongs to one of the user's groups. Environment names are "<group>-<name>". */
    public Environment resolve(Collection<String> userGroups, String environment) {
        if (environment != null)
            for (String group : userGroups)
                if (GROUP.matcher(group).matches() && environment.startsWith(group + "-")
                        && NAME.matcher(environment.substring(group.length() + 1)).matches())
                    return new Environment(group, environment);
        throw new RequestException(403, "You do not have access to this environment.");
    }

    /** Every environment that currently exists for the given groups. */
    public List<Summary> list(Collection<String> userGroups) {
        List<String> groups = userGroups.stream().filter(group -> GROUP.matcher(group).matches()).sorted().toList();
        if (groups.isEmpty()) return List.of();
        JsonNode root = readJson(kubectl.getPodsAndServices(namespace(), GROUP_LABEL + " in (" + String.join(",", groups) + ")"));
        Map<String, List<JsonNode>> byEnvironment = new TreeMap<>();
        for (JsonNode item : root.path("items")) {
            String name = item.path("metadata").path("labels").path(INSTANCE_LABEL).asText("");
            if (!name.isEmpty()) byEnvironment.computeIfAbsent(name, key -> new ArrayList<>()).add(item);
        }
        List<Summary> result = new ArrayList<>();
        byEnvironment.forEach((name, items) -> {
            Status status = parseStatus(name, items, null);
            result.add(new Summary(name, status.group(), status.image(), status.state()));
        });
        return result;
    }

    /**
     * Creates or updates the environment "<group>-<name>" from one of the configured images.
     * authorizedKeys is null (keep the keys from the values file) or already validated public keys.
     */
    public CliRunner.Result start(Collection<String> userGroups, String group, String name, String imageId, String authorizedKeys) {
        if (group == null || !userGroups.contains(group) || !GROUP.matcher(group).matches())
            throw new RequestException(403, "You can only start environments for your own group.");
        if (name == null || !NAME.matcher(name).matches())
            throw new RequestException(400, "The name must be 1-20 lowercase letters, digits or dashes, and start and end with a letter or digit.");
        LinuxProperties.Image image = props.images().stream().filter(candidate -> candidate.id().equals(imageId)).findFirst()
            .orElseThrow(() -> new RequestException(400, "Choose an image from the list."));
        if (!IMAGE_ID.matcher(image.id()).matches() || !IMAGE_REPOSITORY.matcher(image.repository()).matches()
                || !IMAGE_TAG.matcher(image.tag()).matches() || !PACKAGES.matcher(image.packages()).matches())
            throw new CliRunner.CliException("Image '" + image.id() + "' is misconfigured under linux.images");
        String environment = group + "-" + name;
        List<Summary> existing = list(Set.of(group));
        if (existing.size() >= props.maxPerGroup() && existing.stream().noneMatch(summary -> summary.name().equals(environment)))
            throw new RequestException(409, "Group " + group + " already has " + existing.size() + " environments (the limit). Stop one first.");

        Map<String, String> values = new LinkedHashMap<>();
        values.put("group", group);
        values.put("imageId", image.id());
        values.put("image.repository", image.repository());
        values.put("image.tag", image.tag());
        values.put("packages", image.packages().strip());
        values.put("istio.gateway.port", String.valueOf(istioPort(environment)));
        if (authorizedKeys != null) values.put("ssh.authorizedKeys", authorizedKeys);
        return helm.startRelease(CHART_KEY, environment, values);
    }

    public CliRunner.Result stop(Environment environment) { return helm.stopRelease(CHART_KEY, environment.name()); }

    public CliRunner.Result expose(Environment environment) {
        CliRunner.Result result = kubectl.exposeService(namespace(), environment.name());
        if (!result.ok()) throw new CliRunner.CliException("kubectl patch failed:\n" + result.output());
        return result;
    }

    public Status status(Environment environment) {
        JsonNode root = readJson(kubectl.getPodsAndServices(namespace(), selector(environment)));
        // Istio is optional: without its CRDs this call fails and the status simply lists no VirtualService.
        CliRunner.Result istio = kubectl.getVirtualServices(namespace(), selector(environment));
        List<JsonNode> items = new ArrayList<>();
        root.path("items").forEach(items::add);
        try {
            return parseStatus(environment.name(), items, istio.ok() ? mapper.readTree(istio.stdout()) : null);
        } catch (JsonProcessingException e) {
            throw new CliRunner.CliException("kubectl returned output that is not JSON", e);
        }
    }

    public CliRunner.Result exec(Environment environment, String command) {
        return kubectl.exec(namespace(), currentPod(environment), command);
    }

    public CliRunner.Result logs(Environment environment) { return kubectl.logs(namespace(), currentPod(environment)); }

    public CliRunner.Result info(Environment environment) { return kubectl.describe(namespace(), selector(environment)); }

    /**
     * Each environment gets its own TCP port on the Istio ingress gateway, derived from its name,
     * because plain TCP (SSH) cannot share one port between several destinations.
     */
    int istioPort(String environment) {
        CRC32 crc = new CRC32();
        crc.update(environment.getBytes(StandardCharsets.UTF_8));
        return props.istioPortBase() + (int) (crc.getValue() % 10000);
    }

    /**
     * Checks SSH public keys pasted in the browser (one per line) and returns them normalised.
     * @throws RequestException with a user-facing message if a line is not a public key
     */
    public static String validatePublicKeys(String text) {
        List<String> keys = text.lines().map(String::strip).filter(line -> !line.isEmpty()).toList();
        if (keys.isEmpty()) throw new RequestException(400, "Paste an SSH public key.");
        if (keys.size() > MAX_KEYS) throw new RequestException(400, "At most " + MAX_KEYS + " SSH public keys are allowed.");
        for (String key : keys) {
            if (key.contains("PRIVATE KEY"))
                throw new RequestException(400, "That is a private key. Paste the public key instead (the .pub file).");
            if (key.length() > MAX_KEY_LENGTH || !PUBLIC_KEY.matcher(key).matches())
                throw new RequestException(400, "Not an SSH public key. Expected a line like: ssh-ed25519 AAAA... you@example");
        }
        return String.join("\n", keys) + "\n";
    }

    private String namespace() { return helm.release(CHART_KEY).namespace(); }

    /** Both labels must match, so one group can never select another group's objects. */
    private static String selector(Environment environment) {
        return INSTANCE_LABEL + "=" + environment.name() + "," + GROUP_LABEL + "=" + environment.group();
    }

    private JsonNode readJson(CliRunner.Result result) {
        if (!result.ok()) throw new CliRunner.CliException("kubectl get failed:\n" + result.output());
        try {
            return mapper.readTree(result.stdout());
        } catch (JsonProcessingException e) {
            throw new CliRunner.CliException("kubectl returned output that is not JSON", e);
        }
    }

    /** The pod that exec and logs talk to: a ready one if possible, otherwise one that is still coming up. */
    private String currentPod(Environment environment) {
        return status(environment).pods().stream().filter(pod -> !pod.terminating())
            .max(Comparator.comparing(Pod::ready)).map(Pod::name)
            .orElseThrow(() -> new RequestException(409, "This environment has no pod yet. Press Start first."));
    }

    /** Turns "kubectl get ... -o json" items (pods and services, plus VirtualServices if Istio is present) into the summary shown in the browser. */
    static Status parseStatus(String name, List<JsonNode> items, JsonNode istioRoot) {
        List<Pod> pods = new ArrayList<>();
        ServiceInfo service = null;
        String group = "", image = "";
        List<VirtualServiceInfo> virtualServices = new ArrayList<>();
        List<String> sshAccess = new ArrayList<>();
        if (istioRoot != null)
            for (JsonNode item : istioRoot.path("items")) virtualServices.add(parseVirtualService(item, sshAccess));
        for (JsonNode item : items) {
            JsonNode labels = item.path("metadata").path("labels");
            if (group.isEmpty()) group = labels.path(GROUP_LABEL).asText("");
            if (image.isEmpty()) image = labels.path(IMAGE_LABEL).asText("");
            String kind = item.path("kind").asText();
            if (kind.equals("Pod")) pods.add(parsePod(item));
            else if (kind.equals("Service")) { service = parseService(item); addSshAccess(item, sshAccess); }
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
            message = name + " is running" + (exposed ? " and exposed as " + service.type() + "." : ".")
                + " Tools and the SSH server are installed in the background; watch Logs.";
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
            message = name + " is not running. Press Start to deploy it.";
        }
        return new Status(name, group, image, state, message, steps, pods, service, exposed, virtualServices, sshAccess);
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
            String text = (port.path("name").asText("") + " " + port.path("port").asText() + " -> " + port.path("targetPort").asText()).strip();
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

    /** Adds ready-to-copy ssh commands for the service's SSH port (the one whose name contains "ssh"). */
    private static void addSshAccess(JsonNode service, List<String> sshAccess) {
        String name = service.path("metadata").path("name").asText();
        String namespace = service.path("metadata").path("namespace").asText("default");
        for (JsonNode port : service.path("spec").path("ports")) {
            if (!port.path("name").asText("").contains("ssh")) continue;
            int number = port.path("port").asInt(), nodePort = port.path("nodePort").asInt(0);
            sshAccess.add("Port-forward from your computer: kubectl port-forward -n " + namespace + " svc/" + name + " 2222:" + number
                + "   then: ssh -p 2222 root@localhost");
            for (JsonNode ingress : service.path("status").path("loadBalancer").path("ingress")) {
                String address = ingress.path("ip").asText(ingress.path("hostname").asText(""));
                if (!address.isEmpty()) sshAccess.add("From outside (load balancer): ssh root@" + address + " -p " + number);
            }
            if (nodePort > 0) sshAccess.add("From outside (node port): ssh root@<node-address> -p " + nodePort);
            sshAccess.add("Inside the cluster: ssh root@" + name + "." + namespace + ".svc.cluster.local -p " + number);
        }
    }

    private static VirtualServiceInfo parseVirtualService(JsonNode item, List<String> sshAccess) {
        JsonNode spec = item.path("spec");
        List<String> hosts = new ArrayList<>(), gateways = new ArrayList<>(), routes = new ArrayList<>();
        for (JsonNode host : spec.path("hosts")) hosts.add(host.asText());
        for (JsonNode gateway : spec.path("gateways")) gateways.add(gateway.asText());
        for (JsonNode rule : spec.path("tcp")) {
            String gatewayPort = rule.path("match").path(0).path("port").asText("any");
            for (JsonNode route : rule.path("route")) {
                JsonNode destination = route.path("destination");
                routes.add("tcp " + gatewayPort + " -> " + destination.path("host").asText() + ":" + destination.path("port").path("number").asText());
            }
            if (!gatewayPort.equals("any")) sshAccess.add("Through Istio: ssh root@<istio-ingress-address> -p " + gatewayPort);
        }
        for (JsonNode rule : spec.path("http"))
            for (JsonNode route : rule.path("route"))
                routes.add("http -> " + route.path("destination").path("host").asText() + ":" + route.path("destination").path("port").path("number").asText());
        return new VirtualServiceInfo(item.path("metadata").path("name").asText(), hosts, gateways, routes);
    }
}
