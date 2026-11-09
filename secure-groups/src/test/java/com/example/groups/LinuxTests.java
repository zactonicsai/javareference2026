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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
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
    static final String KEY = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIJ3x0pXkq7mYb1v9c2dQ4eR6tZ8uW0aB2cD4eF6gH8iJ me@laptop";
    static final String FAKE_HELM = """
        #!/bin/sh
        if [ "$1" = fail ]; then echo boom >&2; exit 3; fi
        echo "ARGS: $*"
        for argument in "$@"; do case "$argument" in
          *=/*) echo "VALUE ${argument%%=*}=$(cat "${argument#*=}")";;
        esac; done
        """;
    static final String FAKE_KUBECTL = """
        #!/bin/sh
        if [ "$1" != get ]; then echo "ARGS: $*"; exit 0; fi
        if [ "$2" = virtualservices.networking.istio.io ]; then cat <<'JSON'
        {"kind":"List","items":[{"kind":"VirtualService","metadata":{"name":"dev1-linux"},"spec":{"hosts":["*"],"gateways":["dev1-linux-gateway"],
          "tcp":[{"match":[{"port":21234}],"route":[{"destination":{"host":"dev1-linux.linux.svc.cluster.local","port":{"number":22}}}]}]}}]}
        JSON
        exit 0; fi
        echo "warning on stderr must not break JSON parsing" >&2
        cat <<'JSON'
        {"kind":"List","items":[
         {"kind":"Pod","metadata":{"name":"dev1-linux-abc","labels":{"app.kubernetes.io/instance":"dev1-linux","workspace-group":"dev1","workspace-image":"devtools"}},
          "spec":{"nodeName":"node-1"},
          "status":{"phase":"Running","podIP":"10.0.0.5","containerStatuses":[{"ready":true,"restartCount":0,"state":{"running":{}}}]}},
         {"kind":"Service","metadata":{"name":"dev1-linux","namespace":"linux","labels":{"app.kubernetes.io/instance":"dev1-linux","workspace-group":"dev1","workspace-image":"devtools"}},
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
    static RequestPostProcessor dev1() { return user("user").authorities(() -> "GROUP_dev1"); }
    static RequestPostProcessor dev2() { return user("user2").authorities(() -> "GROUP_dev2"); }
    static MockHttpServletRequestBuilder start(String json) {
        return post("/api/linux/environments").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json);
    }

    @Test void imagesComeFromTheBackendList() throws Exception {
        mvc.perform(get("/api/linux/images").with(dev1())).andExpect(status().isOk())
            .andExpect(jsonPath("$[*].id").value(hasItems("devtools", "ubuntu", "java", "python", "node")))
            .andExpect(jsonPath("$[0].image").value("ubuntu:24.04"));
    }
    @Test void startNamesThePodAfterTheGroupAndUsesTheChosenImage() throws Exception {
        String chart = Path.of("helm/charts/linux").toAbsolutePath().normalize().toString();
        mvc.perform(start("{\"name\":\"build\",\"image\":\"java\",\"sshPublicKey\":\"" + KEY + "\"}").with(dev1()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.environment").value("dev1-build"))
            .andExpect(jsonPath("$.output").value(allOf(
                containsString("upgrade --install dev1-build " + chart + " --namespace linux --create-namespace"),
                containsString("VALUE group=dev1"), containsString("VALUE image.repository=maven"),
                containsString("VALUE image.tag=3.9-eclipse-temurin-21"), containsString("VALUE packages=git"),
                containsString("VALUE ssh.authorizedKeys=" + KEY))));
        // Defaults: the user's only group, the name "linux" and the first image in the list.
        mvc.perform(start("{}").with(dev1())).andExpect(status().isOk()).andExpect(jsonPath("$.environment").value("dev1-linux"))
            .andExpect(jsonPath("$.output").value(containsString("VALUE imageId=devtools")));
    }
    @Test void startRejectsBadInput() throws Exception {
        mvc.perform(start("{\"group\":\"dev2\"}").with(dev1())).andExpect(status().isForbidden());
        mvc.perform(start("{\"image\":\"not-in-the-list\"}").with(dev1())).andExpect(status().isBadRequest());
        for (String name : new String[]{"Build", "-x", "a b", "x;id", "../etc", "this-name-is-far-too-long-for-a-pod"})
            mvc.perform(start("{\"name\":\"" + name + "\"}").with(dev1())).andExpect(status().isBadRequest());
        for (String key : new String[]{"hello", "command=\\\"id\\\" " + KEY, "-----BEGIN OPENSSH PRIVATE KEY-----", "ssh-ed25519 AAAA,x=y"})
            mvc.perform(start("{\"sshPublicKey\":\"" + key + "\"}").with(dev1()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").isString());
    }
    @Test void listAndStatusDescribeTheGroupsPods() throws Exception {
        mvc.perform(get("/api/linux/environments").with(dev1())).andExpect(status().isOk())
            .andExpect(jsonPath("$[0].name").value("dev1-linux")).andExpect(jsonPath("$[0].group").value("dev1"))
            .andExpect(jsonPath("$[0].image").value("devtools")).andExpect(jsonPath("$[0].state").value("running"));
        mvc.perform(get("/api/linux/environments/dev1-linux/status").with(dev1())).andExpect(status().isOk())
            .andExpect(jsonPath("$.state").value("running")).andExpect(jsonPath("$.exposed").value(true))
            .andExpect(jsonPath("$.pods[0].name").value("dev1-linux-abc")).andExpect(jsonPath("$.service.type").value("NodePort"))
            .andExpect(jsonPath("$.steps[4].done").value(true))
            .andExpect(jsonPath("$.virtualServices[0].routes[0]").value("tcp 21234 -> dev1-linux.linux.svc.cluster.local:22"))
            .andExpect(jsonPath("$.sshAccess").value(hasItems(containsString("-p 30022"), containsString("-p 21234"),
                containsString("kubectl port-forward -n linux svc/dev1-linux 2222:22"))));
    }
    @Test void terminalLogsInfoExposeAndStopCallTheTools() throws Exception {
        String base = "/api/linux/environments/dev1-linux/";
        mvc.perform(post(base + "exec").with(csrf()).with(dev1()).contentType(MediaType.APPLICATION_JSON).content("{\"command\":\"gcc --version\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.exitCode").value(0))
            .andExpect(jsonPath("$.output").value(containsString("exec -n linux dev1-linux-abc -- sh -c gcc --version")));
        mvc.perform(post(base + "exec").with(csrf()).with(dev1()).contentType(MediaType.APPLICATION_JSON).content("{\"command\":\" \"}"))
            .andExpect(status().isBadRequest());
        mvc.perform(get(base + "logs").with(dev1())).andExpect(status().isOk())
            .andExpect(jsonPath("$.output").value(containsString("logs -n linux dev1-linux-abc --tail=200")));
        mvc.perform(get(base + "info").with(dev1())).andExpect(status().isOk())
            .andExpect(jsonPath("$.output").value(containsString("-l app.kubernetes.io/instance=dev1-linux,workspace-group=dev1")));
        mvc.perform(post(base + "expose").with(csrf()).with(dev1())).andExpect(status().isOk())
            .andExpect(jsonPath("$.output").value(containsString("patch service dev1-linux -n linux --type merge")));
        mvc.perform(post(base + "stop").with(csrf()).with(dev1())).andExpect(status().isOk())
            .andExpect(jsonPath("$.output").value(containsString("uninstall dev1-linux --namespace linux")));
    }
    @Test void otherGroupsCannotTouchAPod() throws Exception {
        String base = "/api/linux/environments/dev1-linux/";
        for (String action : new String[]{"status", "logs", "info"})
            mvc.perform(get(base + action).with(dev2())).andExpect(status().isForbidden());
        for (String action : new String[]{"stop", "expose"})
            mvc.perform(post(base + action).with(csrf()).with(dev2())).andExpect(status().isForbidden());
        mvc.perform(post(base + "exec").with(csrf()).with(dev2()).contentType(MediaType.APPLICATION_JSON).content("{\"command\":\"id\"}"))
            .andExpect(status().isForbidden());
        // "dev" is a different group from "dev1": a name prefix alone is not enough.
        mvc.perform(get(base + "status").with(user("admin").authorities(() -> "GROUP_dev"))).andExpect(status().isForbidden());
    }
    @Test void everythingRequiresSignInAndPostsRequireCsrf() throws Exception {
        mvc.perform(get("/api/linux/images")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/linux/environments")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/linux/environments").with(csrf())).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/linux/environments").with(dev1())).andExpect(status().isForbidden());
        for (String action : new String[]{"stop", "expose", "exec"})
            mvc.perform(post("/api/linux/environments/dev1-linux/" + action).with(dev1())).andExpect(status().isForbidden());
    }
    @Test void helmFailuresAndUnknownChartsAreReported() {
        assertThrows(CliRunner.CliException.class, () -> helm.runHelm("fail", java.util.List.of()));
        assertThrows(CliRunner.CliException.class, () -> helm.startRelease("nope", "x", java.util.Map.of()));
    }
}
