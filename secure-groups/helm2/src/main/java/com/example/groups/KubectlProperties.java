package com.example.groups;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** kubectl settings, bound from the "kubectl" section of application.yml. */
@ConfigurationProperties("kubectl")
public record KubectlProperties(
        @DefaultValue("kubectl") String executable,
        String kubeconfigPath,
        @DefaultValue("30") long timeoutSeconds,
        @DefaultValue("NodePort") String exposeType,
        @DefaultValue("200") int logTailLines) {}
