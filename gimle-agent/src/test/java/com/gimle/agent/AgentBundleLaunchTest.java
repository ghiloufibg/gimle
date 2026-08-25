package com.gimle.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.core.module.ResourceSpec;
import com.gimle.core.vessel.VesselEntrypoint;
import com.gimle.core.vessel.VesselProbes;
import com.gimle.core.vessel.VesselSpec;
import com.gimle.os.ResourceLimitHandle;
import com.gimle.os.portable.PortableJvmFlagsResourceLimiter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * The bundle launch pieces as pure functions ({@code buildBundleCommand}'s java-splice rule, {@code
 * resolveBundleWorkdir}) plus one real spawn proving {@link VesselProcessSupervisor} honors a
 * working directory.
 */
class AgentBundleLaunchTest {

  private static final ResourceSpec LIMIT = new ResourceSpec("64Mi", "1000m");

  @TempDir Path tempDir;

  private final PortableJvmFlagsResourceLimiter limiter = new PortableJvmFlagsResourceLimiter();

  private VesselSpec vessel(List<String> args) {
    return new VesselSpec(args, List.of(), Map.of(), List.of(), VesselProbes.NONE, LIMIT, LIMIT);
  }

  @Test
  void a_bare_java_entrypoint_gets_the_agent_jvm_and_limiter_flags_spliced_in() {
    ResourceLimitHandle handle = limiter.prepare("bundle#0", LIMIT);
    VesselEntrypoint entrypoint =
        new VesselEntrypoint(List.of("java", "-jar", "quarkus-run.jar"), ".");

    List<String> command =
        AgentMain.buildBundleCommand(
            "/opt/jdk/bin/java", limiter, handle, entrypoint, vessel(List.of("--prod")));

    assertEquals("/opt/jdk/bin/java", command.get(0));
    List<String> limiterFlags = limiter.jvmFlags(handle);
    assertEquals(limiterFlags, command.subList(1, 1 + limiterFlags.size()));
    int tail = 1 + limiterFlags.size();
    assertEquals(
        List.of("-jar", "quarkus-run.jar", "--prod"), command.subList(tail, command.size()));
  }

  @Test
  void a_non_jvm_entrypoint_gets_no_limiter_flags() {
    ResourceLimitHandle handle = limiter.prepare("bundle#1", LIMIT);
    VesselEntrypoint entrypoint = new VesselEntrypoint(List.of("bin/run", "--serve"), ".");

    List<String> command =
        AgentMain.buildBundleCommand(
            "/opt/jdk/bin/java", limiter, handle, entrypoint, vessel(List.of()));

    assertEquals(List.of("bin/run", "--serve"), command);
    assertFalse(command.stream().anyMatch(part -> part.startsWith("-Xmx")));
  }

  @Test
  void the_workdir_resolves_inside_the_bundle_root() {
    Path bundleRoot = tempDir.resolve("bundle");
    VesselEntrypoint entrypoint = new VesselEntrypoint(List.of("run"), "app/conf");

    Path workdir = AgentMain.resolveBundleWorkdir(bundleRoot, entrypoint);

    assertEquals(bundleRoot.resolve("app/conf").normalize(), workdir);
    assertTrue(workdir.startsWith(bundleRoot));
  }

  @Test
  @Timeout(value = 30, unit = TimeUnit.SECONDS)
  void a_supervised_process_runs_in_the_configured_working_directory() throws Exception {
    Path workdir = Files.createDirectories(tempDir.resolve("bundle-root"));
    Path applicationLogFile = tempDir.resolve("instances").resolve("bundle-0.log");
    List<String> command =
        List.of(
            javaExecutable(),
            "-cp",
            System.getProperty("java.class.path"),
            CwdDriver.class.getName());
    var tracker =
        new com.gimle.core.restart.RestartTracker(
            Duration.ofMillis(200), 2.0, Duration.ofSeconds(5), 10, Duration.ofMinutes(10));

    try (VesselProcessSupervisor supervisor =
        new VesselProcessSupervisor(
            "bundle#0",
            command,
            Map.of(),
            Optional.of(workdir),
            tracker,
            id -> {},
            applicationLogFile,
            id -> {})) {
      supervisor.start();
      String logged = awaitLogContaining(applicationLogFile, "CWD=", Duration.ofSeconds(15));
      assertTrue(
          logged.contains("CWD=" + workdir.toRealPath()),
          "expected the driver to report the configured workdir; got: " + logged);
    }
  }

  /** Prints its own working directory once, then exits cleanly. */
  public static final class CwdDriver {
    public static void main(String[] args) throws IOException {
      System.out.println("CWD=" + Path.of("").toAbsolutePath().toRealPath());
    }
  }

  private static String awaitLogContaining(Path logFile, String needle, Duration timeout)
      throws Exception {
    Instant deadline = Instant.now().plus(timeout);
    while (Instant.now().isBefore(deadline)) {
      if (Files.isRegularFile(logFile)) {
        String content = Files.readString(logFile);
        if (content.contains(needle)) {
          return content;
        }
      }
      Thread.sleep(100);
    }
    throw new AssertionError("log never contained '" + needle + "' within " + timeout);
  }

  private static String javaExecutable() {
    return ProcessHandle.current()
        .info()
        .command()
        .orElseGet(() -> Path.of(System.getProperty("java.home"), "bin", "java").toString());
  }
}
