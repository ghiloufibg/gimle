package com.gimle.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.gimle.core.module.ResourceSpec;
import com.gimle.os.ResourceLimitHandle;
import com.gimle.os.portable.PortableJvmFlagsResourceLimiter;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * F-03 acceptance test: {@code PortableJvmFlagsResourceLimiter}'s computed {@code -Xmx}/{@code
 * -XX:ActiveProcessorCount} flags are verified against a real spawned worker JVM, not just asserted
 * to be present in a command list ({@code PortableJvmFlagsResourceLimiterTest} already covers
 * that). {@code AgentWorkerIntegrationTest} spawns a real worker but with a hand-built command that
 * never goes through the real limiter at all -- this closes that gap without disturbing that test's
 * own focus on the control-channel handshake.
 */
class ResourceLimitEnforcementTest {

  @Test
  @Timeout(value = 30, unit = TimeUnit.SECONDS)
  void a_spawned_jvm_honors_the_computed_memory_and_processor_ceiling() throws Exception {
    PortableJvmFlagsResourceLimiter limiter = new PortableJvmFlagsResourceLimiter();
    ResourceSpec limit = new ResourceSpec("128Mi", "1000m");
    ResourceLimitHandle handle = limiter.prepare("resource-probe", limit);
    List<String> resourceFlags = limiter.jvmFlags(handle);

    String javaExecutable = javaExecutable();
    String classpath = System.getProperty("java.class.path");
    List<String> command = new ArrayList<>();
    command.add(javaExecutable);
    command.addAll(resourceFlags);
    command.add("-cp");
    command.add(classpath);
    command.add(ResourceProbeDriver.class.getName());

    ProcessBuilder pb = new ProcessBuilder(command);
    pb.redirectErrorStream(true);
    Process process = pb.start();

    List<String> lines = new ArrayList<>();
    try (BufferedReader reader =
        new BufferedReader(
            new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
      String line;
      while ((line = reader.readLine()) != null) {
        lines.add(line);
      }
    }
    boolean finished = process.waitFor(20, TimeUnit.SECONDS);
    if (!finished) {
      process.destroyForcibly();
      fail("resource probe subprocess did not finish in time");
    }
    if (process.exitValue() != 0) {
      fail("resource probe subprocess exited " + process.exitValue() + "; output:\n" + lines);
    }

    long availableProcessors = extractLong(lines, "AVAILABLE_PROCESSORS=");
    long maxMemory = extractLong(lines, "MAX_MEMORY=");

    assertEquals(
        1, availableProcessors, "1000m should compute to exactly 1 processor; output=" + lines);
    // The JVM's own Runtime#maxMemory() is not always byte-identical to -Xmx (small internal
    // rounding), so this checks it's honored within a generous margin rather than exact equality
    // -- still tight enough to catch the flag being silently dropped before spawning.
    long requestedBytes = 128L * 1024 * 1024;
    assertTrue(
        maxMemory <= requestedBytes && maxMemory > requestedBytes * 0.8,
        "expected maxMemory close to the requested "
            + requestedBytes
            + " bytes, got "
            + maxMemory
            + "; output="
            + lines);
  }

  private static long extractLong(List<String> lines, String prefix) {
    return lines.stream()
        .filter(l -> l.startsWith(prefix))
        .map(l -> l.substring(prefix.length()))
        .mapToLong(Long::parseLong)
        .findFirst()
        .orElseThrow(
            () -> new IllegalStateException("no line starting with " + prefix + " in " + lines));
  }

  private static String javaExecutable() {
    Optional<String> command = ProcessHandle.current().info().command();
    if (command.isPresent()) {
      return command.get();
    }
    Path javaBin = Path.of(System.getProperty("java.home"), "bin");
    for (String candidate : List.of("java", "java.exe")) {
      Path path = javaBin.resolve(candidate);
      if (Files.isRegularFile(path)) {
        return path.toString();
      }
    }
    throw new IllegalStateException("could not locate the java launcher under " + javaBin);
  }
}
