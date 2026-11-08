package com.example.groups;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;

/** Calls the kubectl CLI. Every method returns the command line it ran together with its output. */
@Service
public class KubectlCommandService {
    private static final Set<String> EXPOSE_TYPES = Set.of("NodePort", "LoadBalancer");
    private final KubectlProperties props;
    private final CliRunner runner;

    public KubectlCommandService(KubectlProperties props, CliRunner runner) { this.props = props; this.runner = runner; }

    /** Pods and services matching a label selector, as JSON on stdout. */
    public CliRunner.Result getPodsAndServices(String namespace, String selector) {
        return runKubectl("get", "pods,services", "-n", namespace, "-l", selector, "-o", "json");
    }

    /** Runs a shell command inside the pod. The command is interpreted by the pod's shell, not on this server. */
    public CliRunner.Result exec(String namespace, String pod, String command) {
        return runKubectl("exec", "-n", namespace, pod, "--", "sh", "-c", command);
    }

    public CliRunner.Result logs(String namespace, String pod) {
        return runKubectl("logs", "-n", namespace, pod, "--tail=" + props.logTailLines(), "--timestamps");
    }

    public CliRunner.Result describe(String namespace, String selector) {
        return runKubectl("describe", "pods,services", "-n", namespace, "-l", selector);
    }

    /** Makes a service reachable from outside the cluster by switching it to the configured type. */
    public CliRunner.Result exposeService(String namespace, String service) {
        if (!EXPOSE_TYPES.contains(props.exposeType()))
            throw new CliRunner.CliException("kubectl.expose-type must be one of " + EXPOSE_TYPES);
        return runKubectl("patch", "service", service, "-n", namespace, "--type", "merge",
            "-p", "{\"spec\":{\"type\":\"" + props.exposeType() + "\"}}");
    }

    /** Executes "kubectl <args...>". */
    public CliRunner.Result runKubectl(String... args) {
        List<String> command = new ArrayList<>();
        command.add(props.executable());
        command.addAll(List.of(args));
        return runner.run(command, props.kubeconfigPath(), props.timeoutSeconds());
    }
}
