package com.sunweisheng.k8sdeployingjava;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JenkinsAgentConfigurationTest {

    private final String source = read(Path.of("ci", "jenkins-agent.yaml"));
    private final Map<String, Object> pod = loadPod(renderProjectVariables(source));

    @Test
    void runsMavenAndHelmWithNumericNonRootIdentities() {
        Map<String, Object> podSecurity = map(map(pod.get("spec")).get("securityContext"));
        assertEquals(Boolean.TRUE, podSecurity.get("runAsNonRoot"));
        assertEquals(1000, podSecurity.get("runAsUser"));
        assertEquals(1000, podSecurity.get("runAsGroup"));
        assertEquals(1000, podSecurity.get("fsGroup"));
        assertEquals("OnRootMismatch", podSecurity.get("fsGroupChangePolicy"));

        assertRestrictedContainer(container("maven"));
        assertRestrictedContainer(container("helm"));
        assertRestrictedContainer(container("jnlp"));
    }

    @Test
    void usesTheSharedLibraryAgentVariableContract() {
        JsonNode variables = readConfiguration().path("variables");
        assertEquals(1000, variables.path("POD_RUN_AS_USER").asInt());
        assertEquals(1000, variables.path("POD_RUN_AS_GROUP").asInt());
        assertEquals(1000, variables.path("POD_FS_GROUP").asInt());
        assertEquals("/home/jenkins", variables.path("MAVEN_USER_HOME").asText());
        assertEquals("/home/jenkins/.m2", variables.path("MAVEN_CONFIG").asText());
        assertEquals("/home/jenkins/.m2/repository", variables.path("MAVEN_REPOSITORY").asText());
        assertEquals("/home/jenkins", variables.path("HELM_USER_HOME").asText());
        assertTrue(variables.path("JNLP_IMAGE").asText().contains("@sha256:"));
        assertEquals("/home/jenkins/agent", variables.path("JNLP_WORKING_DIR").asText());
        assertFalse(variables.has("MAVEN_SETTINGS_CONFIG_MAP"));
        assertFalse(source.contains("AGENT_RUN_AS_"));
    }

    @Test
    void keepsMavenStateInTheWritableNonRootHome() {
        Map<String, Object> maven = container("maven");
        assertEquals("/home/jenkins", environment(maven, "HOME"));
        assertEquals("/home/jenkins/.m2", environment(maven, "MAVEN_CONFIG"));
        assertTrue(environment(maven, "MAVEN_OPTS").contains("-Duser.home=/home/jenkins"));
        assertTrue(environment(maven, "MAVEN_OPTS").contains(
                "-Dmaven.repo.local=/home/jenkins/.m2/repository"
        ));
        assertEquals("/home/jenkins", mountPath(maven, "maven-home"));
        assertEquals("/home/jenkins/.m2/repository", mountPath(maven, "maven-cache"));
        assertTrue(volume("maven-home").containsKey("emptyDir"));
        assertEquals(
                "maven-cache",
                map(volume("maven-cache").get("persistentVolumeClaim")).get("claimName")
        );
        assertFalse(hasVolumeMount(maven, "maven-settings"));
        assertFalse(hasVolume("maven-settings"));
        assertFalse(hasEnvironment(maven, "HTTP_PROXY"));
        assertFalse(hasEnvironment(maven, "HTTPS_PROXY"));
        assertFalse(hasEnvironment(maven, "NO_PROXY"));
        assertFalse(source.contains("/root/.m2"));
    }

    @Test
    void givesHelmAWritableNonRootHome() {
        Map<String, Object> helm = container("helm");
        assertEquals("/home/jenkins", environment(helm, "HOME"));
        assertEquals("/home/jenkins", mountPath(helm, "helm-home"));
        assertTrue(volume("helm-home").containsKey("emptyDir"));
    }

    @Test
    void mountsEnvironmentSpecificHelmValuesFromAnOptionalConfigMap() {
        Map<String, Object> helm = container("helm");
        assertEquals("/etc/helm/deploy-overrides", mountPath(helm, "helm-overrides"));

        Map<String, Object> configMap = map(volume("helm-overrides").get("configMap"));
        assertEquals("deploy-overrides", configMap.get("name"));
        assertEquals(Boolean.TRUE, configMap.get("optional"));
    }

    @Test
    void preservesTheRootlessBuildKitContract() {
        Map<String, Object> buildkit = container("buildkit");
        Map<String, Object> security = map(buildkit.get("securityContext"));
        assertEquals(Boolean.TRUE, security.get("runAsNonRoot"));
        assertEquals("${BUILDKIT_RUN_AS_USER}", security.get("runAsUser"));
        assertEquals("${BUILDKIT_RUN_AS_GROUP}", security.get("runAsGroup"));
        assertEquals(Boolean.TRUE, security.get("allowPrivilegeEscalation"));
        assertEquals("Unconfined", map(security.get("seccompProfile")).get("type"));
        assertEquals("Unconfined", map(security.get("appArmorProfile")).get("type"));
        assertEquals(List.of("SETUID", "SETGID"), map(security.get("capabilities")).get("add"));
        assertEquals(List.of("ALL"), map(security.get("capabilities")).get("drop"));
        assertTrue(hasEnvironment(buildkit, "HTTP_PROXY"));
        assertTrue(hasEnvironment(buildkit, "HTTPS_PROXY"));
        assertTrue(hasEnvironment(buildkit, "NO_PROXY"));
        String flags = environment(buildkit, "BUILDKITD_FLAGS");
        assertTrue(flags.contains("${BUILDKITD_FLAGS}"));
        assertTrue(flags.contains("--root ${BUILDKIT_STATE_DIR}"));
    }

    @Test
    void routesJnlpSourceCheckoutThroughTheBuildProxy() {
        List<String> names = containers().stream()
                .map(container -> container.get("name").toString())
                .toList();
        assertEquals(List.of("jnlp", "maven", "buildkit", "helm"), names);

        Map<String, Object> jnlp = container("jnlp");
        assertTrue(jnlp.get("image").toString().contains("@sha256:"));
        assertEquals("/home/jenkins/agent", jnlp.get("workingDir"));
        assertEquals("build-proxy", environmentConfigMap(jnlp));
    }

    private void assertRestrictedContainer(Map<String, Object> container) {
        Map<String, Object> security = map(container.get("securityContext"));
        assertEquals(Boolean.TRUE, security.get("runAsNonRoot"));
        assertEquals(1000, security.get("runAsUser"));
        assertEquals(1000, security.get("runAsGroup"));
        assertEquals(Boolean.FALSE, security.get("allowPrivilegeEscalation"));
        assertEquals(List.of("ALL"), map(security.get("capabilities")).get("drop"));
    }

    private Map<String, Object> container(String name) {
        return containers().stream()
                .filter(container -> name.equals(container.get("name")))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Missing container: " + name));
    }

    private List<Map<String, Object>> containers() {
        return maps(map(pod.get("spec")).get("containers"));
    }

    private String environment(Map<String, Object> container, String name) {
        return maps(container.get("env")).stream()
                .filter(entry -> name.equals(entry.get("name")))
                .map(entry -> entry.get("value").toString())
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Missing environment variable: " + name));
    }

    private String mountPath(Map<String, Object> container, String name) {
        return maps(container.get("volumeMounts")).stream()
                .filter(mount -> name.equals(mount.get("name")))
                .map(mount -> mount.get("mountPath").toString())
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Missing volume mount: " + name));
    }

    private boolean hasEnvironment(Map<String, Object> container, String name) {
        return maps(container.get("env")).stream()
                .anyMatch(entry -> name.equals(entry.get("name")));
    }

    private String environmentConfigMap(Map<String, Object> container) {
        return maps(container.get("envFrom")).stream()
                .map(entry -> map(entry.get("configMapRef")).get("name").toString())
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Missing environment ConfigMap"));
    }

    private boolean hasVolumeMount(Map<String, Object> container, String name) {
        return maps(container.get("volumeMounts")).stream()
                .anyMatch(mount -> name.equals(mount.get("name")));
    }

    private boolean hasVolume(String name) {
        return maps(map(pod.get("spec")).get("volumes")).stream()
                .anyMatch(volume -> name.equals(volume.get("name")));
    }

    private Map<String, Object> volume(String name) {
        return maps(map(pod.get("spec")).get("volumes")).stream()
                .filter(volume -> name.equals(volume.get("name")))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Missing volume: " + name));
    }

    private String renderProjectVariables(String yaml) {
        JsonNode variables = readConfiguration().path("variables");

        String rendered = yaml;
        for (Map.Entry<String, JsonNode> variable : variables.properties()) {
            rendered = rendered.replace("${" + variable.getKey() + "}", variable.getValue().asText());
        }
        return rendered;
    }

    private JsonNode readConfiguration() {
        try {
            return new ObjectMapper().readTree(Files.readString(Path.of("ci", "jenkins-project.json")));
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot read pipeline configuration", exception);
        }
    }

    private Map<String, Object> loadPod(String yaml) {
        return map(new Yaml().load(yaml));
    }

    private String read(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot read file: " + path, exception);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> maps(Object value) {
        return (List<Map<String, Object>>) value;
    }
}
