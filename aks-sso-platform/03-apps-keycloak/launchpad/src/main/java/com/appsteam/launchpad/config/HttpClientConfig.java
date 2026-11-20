package com.appsteam.launchpad.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;

/**
 * The RestClient used by the status checker: short timeouts, no redirects (a 3xx already proves the service is
 * alive), JDK HttpClient underneath (no extra dependency).
 */
@Configuration
public class HttpClientConfig {

    @Bean
    RestClient statusRestClient(AppProperties props) {
        HttpClient jdk = HttpClient.newBuilder()
                .connectTimeout(props.status().connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .version(HttpClient.Version.HTTP_1_1)
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(jdk);
        factory.setReadTimeout(props.status().readTimeout());
        return RestClient.builder()
                .requestFactory(factory)
                .defaultHeader("User-Agent", "launchpad-status-check/1.0")
                .build();
    }
}
