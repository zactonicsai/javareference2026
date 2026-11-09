package com.example.groups;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Linux environment settings, bound from the "linux" section of application.yml. */
@ConfigurationProperties("linux")
public record LinuxProperties(
        @DefaultValue("5") int maxPerGroup,
        @DefaultValue("20000") int istioPortBase,
        List<Image> images) {

    public LinuxProperties {
        images = images == null ? List.of() : List.copyOf(images);
    }

    /**
     * One entry of the image list offered in the browser.
     * packages is a space-separated list of apt packages installed when the pod starts (if not already in the image).
     */
    public record Image(String id, String label, @DefaultValue("") String description,
                        String repository, String tag, @DefaultValue("") String packages) {}
}
