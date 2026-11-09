package com.example.groups;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/** Calls the Helm CLI. Charts and values files are resolved from the configured reference directories. */
@Service
public class HelmCommandService {
    private final HelmProperties props;
    private final CliRunner runner;

    public HelmCommandService(HelmProperties props, CliRunner runner) { this.props = props; this.runner = runner; }

    /** Returns the release declared under helm.releases.<key>. */
    public HelmProperties.Release release(String key) {
        HelmProperties.Release release = props.releases().get(key);
        if (release == null) throw new CliRunner.CliException("No Helm release is configured for '" + key + "'");
        return release;
    }

    /**
     * helm upgrade --install: deploys the chart configured under helm.releases.<key> as a release called releaseName.
     * Each extra value is handed to Helm through a temporary file (--set-file), so its content is never
     * parsed as Helm --set syntax or as a command-line flag.
     */
    public CliRunner.Result startRelease(String key, String releaseName, Map<String, String> fileValues) {
        HelmProperties.Release release = release(key);
        Path chart = resolve(props.chartsDir(), release.chart(), "chart");
        if (!Files.isDirectory(chart)) throw new CliRunner.CliException("Chart directory not found: " + chart);
        List<String> args = new ArrayList<>(List.of("--install", releaseName, chart.toString(),
            "--namespace", release.namespace(), "--create-namespace"));
        if (release.valuesFile() != null && !release.valuesFile().isBlank()) {
            Path values = resolve(props.valuesDir(), release.valuesFile(), "values file");
            if (!Files.isRegularFile(values)) throw new CliRunner.CliException("Values file not found: " + values);
            args.addAll(List.of("--values", values.toString()));
        }
        List<Path> temporary = new ArrayList<>();
        try {
            for (Map.Entry<String, String> value : fileValues.entrySet()) {
                Path file = Files.createTempFile("helm-value-", ".txt");
                temporary.add(file);
                Files.writeString(file, value.getValue());
                args.addAll(List.of("--set-file", value.getKey() + "=" + file));
            }
            return runHelm("upgrade", args);
        } catch (IOException e) {
            throw new CliRunner.CliException("Could not write a temporary Helm value file", e);
        } finally {
            for (Path file : temporary) try { Files.deleteIfExists(file); } catch (IOException ignored) { }
        }
    }

    /** helm uninstall; an already-removed release is not an error. */
    public CliRunner.Result stopRelease(String key, String releaseName) {
        HelmProperties.Release release = release(key);
        CliRunner.Result result = run("uninstall", List.of(releaseName, "--namespace", release.namespace()));
        if (result.ok()) return result;
        if (result.output().contains("not found"))
            return new CliRunner.Result(result.command(), 0, "Release '" + releaseName + "' is not running.", "");
        throw failure("uninstall", result);
    }

    /** Executes "helm <action> <args...>" and fails if Helm reports an error. */
    public CliRunner.Result runHelm(String action, List<String> args) {
        CliRunner.Result result = run(action, args);
        if (!result.ok()) throw failure(action, result);
        return result;
    }

    private CliRunner.Result run(String action, List<String> args) {
        List<String> command = new ArrayList<>(List.of(props.executable(), action));
        command.addAll(args);
        return runner.run(command, props.kubeconfigPath(), props.timeoutSeconds());
    }

    private CliRunner.CliException failure(String action, CliRunner.Result result) {
        return new CliRunner.CliException("Helm '" + action + "' failed with exit code " + result.exitCode() + ":\n" + result.output());
    }

    /** Resolves a relative reference inside its base directory and refuses anything that escapes it. */
    private Path resolve(String baseDir, String relative, String what) {
        Path base = Path.of(baseDir).toAbsolutePath().normalize();
        Path resolved = base.resolve(relative).normalize();
        if (!resolved.startsWith(base)) throw new CliRunner.CliException("The " + what + " path must stay inside " + base);
        return resolved;
    }
}
