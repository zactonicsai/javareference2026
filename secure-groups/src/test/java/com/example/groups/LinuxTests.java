package com.example.groups;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Uses stand-in "helm" and "kubectl" scripts that echo their arguments, so no cluster is needed. */
@DisabledOnOs(OS.WINDOWS)
@SpringBootTest(properties = {"ADMIN_PASSWORD=Test-only-admin-1234", "USER_PASSWORD=Test-only-user-1234", "USER2_PASSWORD=Test-only-user2-1234"})
@AutoConfigureMockMvc
class LinuxTests {
    static final String FAKE_HELM = """
        #!/bin/sh
        if [ "$1" = fail ]; then echo boom >&2; exit 3; fi
        echo "ARGS: $*"
        """;
    static final String FAKE_KUBECTL = """
        #!/bin/sh
        if [ "$1" != get ]; then echo "ARGS: $*"; exit 0; fi
        if [ "$2" = virtualservices.networking.istio.io ]; then cat <<'JSON'
        {"kind":"List","items":[{"kind":"VirtualService","metadata":{"name":"linux"},"spec":{"hosts":["*"],"gateways":["linux-gateway"],
          "tcp":[{"match":[{"port":2222}],"route":[{"destination":{"host":"linux.linux.svc.cluster.local","port":{"number":22}}}]}]}}]}
        JSON
        exit 0; fi
        echo "warning on stderr must not break JSON parsing" >&2
        cat <<'JSON'
        {"kind":"List","items":[
         {"kind":"Pod","metadata":{"name":"linux-abc"},"spec":{"nodeName":"node-1"},
          "status":{"phase":"Running","podIP":"10.0.0.5","containerStatuses":[{"ready":true,"restartCount":0,"state":{"running":{}}}]}},
         {"kind":"Service","metadata":{"name":"linux","namespace":"linux"},
          "spec":{"type":"NodePort","clusterIP":"10.1.0.9","ports":[{"name":"http-app","port":8080,"targetPort":8080,"nodePort":30080},
            {"name":"tcp-ssh","port":22,"targetPort":22,"nodePort":30022}]}}]}
        JSON
        """;

    @Autowired MockMvc mvc;
    @Autowired HelmCommandService helm;

    @DynamicPropertySource
    static void fakeTools(DynamicPropertyRegistry registry) throws Exception {
        registry.add("helm.executable", script("fake-helm", FAKE_HELM)::toString);
        registry.add("kubectl.executable", script("fake-kubectl", FAKE_KUBECTL)::toString);
    }
    static Path script(String name, String body) throws Exception {
        Path script = Files.createTempFile(name, ".sh");
        Files.writeString(script, body);
        script.toFile().setExecutable(true);
        script.toFile().deleteOnExit();
        return script;
    }
    static RequestPostProcessor member() { return user("user").authorities(() -> "GROUP_dev1"); }

    @Test void startUsesReferencePathsAndStopUninstalls() throws Exception {
        String chart = Path.of("helm/charts/linux").toAbsolutePath().normalize().toString();
        String values = Path.of("helm/values/linux.yaml").toAbsolutePath().normalize().toString();
        mvc.perform(post("/api/linux/start").with(csrf()).with(member())).andExpect(status().isOk())
            .andExpect(jsonPath("$.output").value(allOf(
                containsString("upgrade --install linux " + chart + " --namespace linux --create-namespace"),
                containsString("--values " + values))));
        mvc.perform(post("/api/linux/stop").with(csrf()).with(member())).andExpect(status().isOk())
            .andExpect(jsonPath("$.output").value(containsString("uninstall linux --namespace linux")));
    }
    @Test void startAcceptsOnlyValidSshPublicKeys() throws Exception {
        String key = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIJ3x0pXkq7mYb1v9c2dQ4eR6tZ8uW0aB2cD4eF6gH8iJ me@laptop";
        mvc.perform(post("/api/linux/start").with(csrf()).with(member()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"sshPublicKey\":\"" + key + "\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.output").value(containsString("--set-file ssh.authorizedKeys=")));
        for (String bad : new String[]{"hello", "command=\\\"id\\\" " + key, "-----BEGIN OPENSSH PRIVATE KEY-----", "ssh-ed25519 AAAA,x=y"})
            mvc.perform(post("/api/linux/start").with(csrf()).with(member()).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"sshPublicKey\":\"" + bad + "\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").isString());
    }
    @Test void statusSummarisesPodAndService() throws Exception {
        mvc.perform(get("/api/linux/status").with(member())).andExpect(status().isOk())
            .andExpect(jsonPath("$.state").value("running")).andExpect(jsonPath("$.exposed").value(true))
            .andExpect(jsonPath("$.pods[0].name").value("linux-abc")).andExpect(jsonPath("$.pods[0].ready").value(true))
            .andExpect(jsonPath("$.service.type").value("NodePort"))
            .andExpect(jsonPath("$.service.ports[0]").value(containsString("node port 30080")))
            .andExpect(jsonPath("$.steps[4].done").value(true))
            .andExpect(jsonPath("$.virtualServices[0].routes[0]").value("tcp 2222 -> linux.linux.svc.cluster.local:22"))
            .andExpect(jsonPath("$.sshAccess").value(hasItems(containsString("-p 30022"), containsString("-p 2222"),
                containsString("root@linux.linux.svc.cluster.local -p 22"))));
    }
    @Test void terminalLogsInfoAndExposeCallKubectl() throws Exception {
        mvc.perform(post("/api/linux/exec").with(csrf()).with(member()).contentType(MediaType.APPLICATION_JSON).content("{\"command\":\"uname -a\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.exitCode").value(0))
            .andExpect(jsonPath("$.output").value(containsString("exec -n linux linux-abc -- sh -c uname -a")));
        mvc.perform(post("/api/linux/exec").with(csrf()).with(member()).contentType(MediaType.APPLICATION_JSON).content("{\"command\":\" \"}"))
            .andExpect(status().isBadRequest());
        mvc.perform(get("/api/linux/logs").with(member())).andExpect(status().isOk())
            .andExpect(jsonPath("$.output").value(containsString("logs -n linux linux-abc --tail=200")));
        mvc.perform(get("/api/linux/info").with(member())).andExpect(status().isOk())
            .andExpect(jsonPath("$.output").value(containsString("describe pods,services -n linux -l app.kubernetes.io/instance=linux")));
        mvc.perform(post("/api/linux/expose").with(csrf()).with(member())).andExpect(status().isOk())
            .andExpect(jsonPath("$.output").value(containsString("patch service linux -n linux --type merge")));
    }
    @Test void everythingRequiresSignInAndPostsRequireCsrf() throws Exception {
        mvc.perform(get("/api/linux/status")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/linux/start").with(csrf())).andExpect(status().isUnauthorized());
        for (String action : new String[]{"start", "stop", "expose", "exec"})
            mvc.perform(post("/api/linux/" + action).with(member())).andExpect(status().isForbidden());
    }
    @Test void helmFailuresAndUnknownReleasesAreReported() {
        assertThrows(CliRunner.CliException.class, () -> helm.runHelm("fail", java.util.List.of()));
        assertThrows(CliRunner.CliException.class, () -> helm.startRelease("nope"));
    }
}
