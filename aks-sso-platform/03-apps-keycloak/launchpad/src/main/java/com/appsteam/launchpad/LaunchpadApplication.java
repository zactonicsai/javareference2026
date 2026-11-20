package com.appsteam.launchpad;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Apps Team Launchpad: a Tailwind landing page with links to platform services, a login-restricted page,
 * project pages restricted by Keycloak group, live service status and a friendly "service unavailable" page.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class LaunchpadApplication {

    public static void main(String[] args) {
        SpringApplication.run(LaunchpadApplication.class, args);
    }
}
