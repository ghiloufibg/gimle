package com.gimle.smoketests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.testkit.Await;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * The multi-file bundle path end to end, against the real smoke cluster: a genuinely
 * Class-Path-dependent launcher layout (a main jar whose manifest names a {@code lib/} sibling it
 * cannot start without -- the same shape as Quarkus's fast-jar output, with no Quarkus dependency
 * needed to prove the mechanism), zipped with its {@code gimle-entrypoint.yaml} at the archive
 * root, pushed as a {@code BUNDLE}-kind artifact through the control plane's own {@code
 * /artifacts/*} proxy, deployed coordinate-only as a vessel, unpacked by the real agent's
 * pull-through cache, and launched via its own entrypoint -- reaching {@code ACTIVE}, which a
 * missing sibling jar would make impossible (the JVM exits immediately on an unresolvable main
 * class dependency, and a crash-looping vessel reports {@code FAILED}, never {@code ACTIVE}).
 */
@Tag("smoke")
class BundleVesselIT extends GreeterSmokeClusterSupport {

  private static final String MODULE_NAME = "com.gimle.smoketests.bundlefixture";
  private static final String MODULE_VERSION = "1.0.0";
  private static final String DEPLOYMENT_NAME = "bundle-vessel-deployment";

  @Test
  @Timeout(value = 6, unit = TimeUnit.MINUTES)
  void a_coordinate_only_bundle_vessel_unpacks_from_andvari_and_reaches_active() throws Exception {
    Path repoRoot = repoRoot();
    String javaExecutable = javaExecutable();
    String classpath = System.getProperty("java.class.path");

    SmokeCluster cluster = startCluster(repoRoot, javaExecutable, classpath);
    String writeUrl = cluster.controlPlaneBaseUrls().get(0);
    String readUrl = cluster.controlPlaneBaseUrls().get(CONTROLPLANE_COUNT - 1);

    Path bundleZip = buildMultiFileBundleZip();

    HttpResponse<String> pushed =
        httpClient.send(
            HttpRequest.newBuilder(
                    URI.create(writeUrl + "/artifacts/" + MODULE_NAME + "/" + MODULE_VERSION))
                .header("X-Gimle-Artifact-Kind", "BUNDLE")
                .PUT(HttpRequest.BodyPublishers.ofFile(bundleZip))
                .build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    assertEquals(200, pushed.statusCode(), "bundle push failed: " + pushed.body());
    assertTrue(pushed.body().contains("BUNDLE"), "push response should record the kind");

    // Level-triggered submission retry, matching the other ITs' posture toward a cluster whose
    // store/reconcilers are still warming up.
    Await.until(
        () -> {
          try {
            return submitVesselDeployment(writeUrl) == 200;
          } catch (Exception e) {
            return false;
          }
        },
        Duration.ofSeconds(30),
        "vessel deployment submission should be accepted");

    Await.until(
        () -> isActive(readUrl, DEPLOYMENT_NAME),
        Duration.ofSeconds(90),
        DEPLOYMENT_NAME
            + " should reach ACTIVE from a registry-resolved bundle -- impossible unless the"
            + " lib/ sibling jar was unpacked next to the main jar and the entrypoint ran in the"
            + " bundle's own directory");

    // The unpacked bundle in the agent's own pull-through cache is the resolution proof: the
    // deployment named no local path anywhere.
    Path cachedBundle =
        tempDir
            .resolve("gimle-data-smoke-node-1")
            .resolve("artifact-cache")
            .resolve(MODULE_NAME)
            .resolve(MODULE_VERSION)
            .resolve("bundle");
    assertTrue(
        Files.isRegularFile(cachedBundle.resolve("gimle-entrypoint.yaml")),
        "expected the unpacked entrypoint at " + cachedBundle);
    assertTrue(
        Files.isRegularFile(cachedBundle.resolve("lib/dep.jar")),
        "expected the unpacked lib/ sibling at " + cachedBundle);
  }

  private int submitVesselDeployment(String baseUrl) throws Exception {
    String manifest =
        """
        kind: Deployment
        name: %s
        module:
          name: %s
          version: %s
        replicas: 1
        vessel:
          resources:
            request: {memory: 32Mi, cpu: 100m}
            limit: {memory: 64Mi, cpu: 500m}
        """
            .formatted(DEPLOYMENT_NAME, MODULE_NAME, MODULE_VERSION);
    HttpResponse<String> response =
        httpClient.send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/deployments/" + DEPLOYMENT_NAME))
                .PUT(HttpRequest.BodyPublishers.ofString(manifest, StandardCharsets.UTF_8))
                .build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    return response.statusCode();
  }

  /**
   * Compiles and packages the fixture at test runtime: {@code main.jar} (manifest {@code
   * Main-Class} + {@code Class-Path: lib/dep.jar}) and {@code lib/dep.jar}, zipped beside a {@code
   * gimle-entrypoint.yaml} launching the main jar. {@code Main} touches the dep class before
   * entering its stay-alive loop, so a bundle unpacked without its {@code lib/} sibling crashes
   * instantly instead of idling as a false ACTIVE.
   */
  private Path buildMultiFileBundleZip() throws IOException {
    Path fixtureRoot = Files.createDirectories(tempDir.resolve("bundle-fixture"));
    Path sources = Files.createDirectories(fixtureRoot.resolve("src"));
    Path depSource = sources.resolve("Dep.java");
    Files.writeString(
        depSource,
        """
        package bundlefixture.dep;

        public final class Dep {
          public static String value() {
            return "dep-loaded";
          }
        }
        """);
    Path mainSource = sources.resolve("Main.java");
    Files.writeString(
        mainSource,
        """
        package bundlefixture;

        public final class Main {
          public static void main(String[] args) throws InterruptedException {
            System.out.println("BUNDLE-MAIN-OK " + bundlefixture.dep.Dep.value());
            while (true) {
              Thread.sleep(1000L);
            }
          }
        }
        """);
    Path classes = Files.createDirectories(fixtureRoot.resolve("classes"));
    int compiled =
        ToolProvider.getSystemJavaCompiler()
            .run(
                null,
                null,
                null,
                "-d",
                classes.toString(),
                depSource.toString(),
                mainSource.toString());
    assertEquals(0, compiled, "fixture compilation failed");

    Path bundleDir = Files.createDirectories(fixtureRoot.resolve("bundle"));
    Files.createDirectories(bundleDir.resolve("lib"));
    writeJar(
        bundleDir.resolve("lib/dep.jar"), null, classes, List.of("bundlefixture/dep/Dep.class"));
    Manifest mainManifest = new Manifest();
    mainManifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
    mainManifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, "bundlefixture.Main");
    mainManifest.getMainAttributes().put(Attributes.Name.CLASS_PATH, "lib/dep.jar");
    writeJar(
        bundleDir.resolve("main.jar"), mainManifest, classes, List.of("bundlefixture/Main.class"));

    Path zipFile = fixtureRoot.resolve("bundle.zip");
    try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(zipFile))) {
      zip.putNextEntry(new ZipEntry("gimle-entrypoint.yaml"));
      zip.write("command: [java, -jar, main.jar]\n".getBytes(StandardCharsets.UTF_8));
      zip.closeEntry();
      for (String entryName : List.of("main.jar", "lib/dep.jar")) {
        zip.putNextEntry(new ZipEntry(entryName));
        Files.copy(bundleDir.resolve(entryName), zip);
        zip.closeEntry();
      }
    }
    return zipFile;
  }

  private static void writeJar(
      Path jarFile, Manifest manifest, Path classesRoot, List<String> classEntries)
      throws IOException {
    try (OutputStream out = Files.newOutputStream(jarFile);
        JarOutputStream jar =
            manifest != null ? new JarOutputStream(out, manifest) : new JarOutputStream(out)) {
      for (String entryName : classEntries) {
        jar.putNextEntry(new ZipEntry(entryName));
        Files.copy(classesRoot.resolve(entryName), jar);
        jar.closeEntry();
      }
    }
  }
}
