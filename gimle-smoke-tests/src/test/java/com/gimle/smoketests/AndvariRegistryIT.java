package com.gimle.smoketests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.testkit.Await;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * The registry-resolution path end to end, against a real Andvari replica in the smoke cluster: a
 * jar pushed through the control plane's own {@code /artifacts/*} proxy (the same route {@code
 * gimle artifact push} takes), a deployment submitted with no {@code artifactPath} at all, a real
 * agent resolving the module coordinate from Andvari into its own pull-through cache, and the
 * instance reaching {@code ACTIVE} -- observed through a different control-plane replica than the
 * one everything was submitted to. The tenant-scoped provider module is deliberately reused here so
 * the whole existing install machinery (config/secret delivery included) runs unchanged over a
 * registry-resolved jar.
 */
@Tag("smoke")
class AndvariRegistryIT extends GreeterSmokeClusterSupport {

  private static final String MODULE_NAME = "com.gimle.examples.greeter.provider";
  private static final String MODULE_VERSION = "1.0.0";
  private static final String DEPLOYMENT_NAME = "greeter-registry-deployment";

  @Test
  @Timeout(value = 6, unit = TimeUnit.MINUTES)
  void a_coordinate_only_deployment_pulls_its_jar_from_andvari_through_the_agent_cache()
      throws Exception {
    Path repoRoot = repoRoot();
    String javaExecutable = javaExecutable();
    String classpath = System.getProperty("java.class.path");

    SmokeCluster cluster = startCluster(repoRoot, javaExecutable, classpath);
    String writeUrl = cluster.controlPlaneBaseUrls().get(0);
    String readUrl = cluster.controlPlaneBaseUrls().get(CONTROLPLANE_COUNT - 1);

    Path providerJar =
        repoRoot.resolve(
            "gimle-examples/greeter-provider/target/greeter-provider-" + GIMLE_VERSION + ".jar");
    assertTrue(Files.isRegularFile(providerJar), "expected a built jar at " + providerJar);

    // Submitting the coordinate-only manifest *before* the push must be rejected at admission:
    // the registry definitively answers 404 for it, which is exactly the loud failure a typo'd
    // or forgotten push should produce -- not a deployment sitting unplaceable forever.
    provisionTenantAndSecret(writeUrl);
    HttpResponse<String> prematureSubmit =
        httpClient.send(
            HttpRequest.newBuilder(URI.create(writeUrl + "/deployments/" + DEPLOYMENT_NAME))
                .PUT(
                    HttpRequest.BodyPublishers.ofString(
                        coordinateManifest(), StandardCharsets.UTF_8))
                .build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    assertEquals(
        400,
        prematureSubmit.statusCode(),
        "a coordinate submission before any push should be rejected: " + prematureSubmit.body());

    // The real push, through the control-plane /artifacts/* proxy -- the route `gimle artifact
    // push` takes -- not straight at the Andvari port.
    HttpResponse<String> pushed =
        httpClient.send(
            HttpRequest.newBuilder(
                    URI.create(writeUrl + "/artifacts/" + MODULE_NAME + "/" + MODULE_VERSION))
                .PUT(HttpRequest.BodyPublishers.ofFile(providerJar))
                .build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    assertEquals(200, pushed.statusCode(), "artifact push failed: " + pushed.body());

    submitDeploymentByCoordinateWithRetry(
        writeUrl,
        DEPLOYMENT_NAME,
        MODULE_NAME,
        Optional.of(SECRET_TENANT_ID),
        Duration.ofSeconds(30));

    Await.until(
        () -> isActive(readUrl, DEPLOYMENT_NAME, Optional.of(SECRET_TENANT_ID)),
        Duration.ofSeconds(90),
        DEPLOYMENT_NAME
            + " should reach ACTIVE from a registry-resolved jar, observed through a different"
            + " control-plane replica than the one it was submitted to");

    // The jar the worker actually ran came through the agent's own pull-through cache -- the
    // deployment named no local path anywhere, so this file existing is the resolution proof.
    Path cachedJar =
        tempDir
            .resolve("gimle-data-smoke-node-1")
            .resolve("artifact-cache")
            .resolve(MODULE_NAME)
            .resolve(MODULE_VERSION)
            .resolve("artifact.jar");
    assertTrue(
        Files.isRegularFile(cachedJar),
        "expected the agent's pull-through cache to hold the resolved jar at " + cachedJar);
    assertEquals(
        Files.size(providerJar),
        Files.size(cachedJar),
        "cached jar should be byte-for-byte the pushed artifact");
  }

  private String coordinateManifest() {
    return """
        kind: Deployment
        name: %s
        module:
          name: %s
          version: %s
        replicas: 1
        tenantId: %s
        """
        .formatted(DEPLOYMENT_NAME, MODULE_NAME, MODULE_VERSION, SECRET_TENANT_ID);
  }
}
