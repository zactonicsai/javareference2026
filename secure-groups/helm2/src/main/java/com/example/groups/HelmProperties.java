package com.example.groups;

import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Helm settings, bound from the "helm" section of application.yml.
 * Directories are reference paths: charts and values files are always
 * resolved relative to chartsDir / valuesDir, never hard-coded in code.
 */
@ConfigurationProperties("helm")
public record HelmProperties(
        @DefaultValue("helm") String executable,
        String kubeconfigPath,
        @DefaultValue("90") long timeoutSeconds,
        @DefaultValue("./helm/charts") String chartsDir,
        @DefaultValue("./helm/values") String valuesDir,
        @DefaultValue Map<String, Release> releases) {

    /** One deployable release; chart and valuesFile are relative to the reference directories. */
    public record Release(String releaseName, String chart, @DefaultValue("default") String namespace, String valuesFile) {}
}
