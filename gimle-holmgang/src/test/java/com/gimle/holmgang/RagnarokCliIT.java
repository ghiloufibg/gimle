package com.gimle.holmgang;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.holmgang.cluster.GimleCluster;
import com.gimle.holmgang.topology.ClusterSpec;
import com.gimle.holmgang.topology.ClusterTopologyParser;
import com.gimle.ragnarok.RagnarokMain;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Proves the {@code ragnarok} binary itself, not just the library it wraps: {@code RagnarokMain}
 * invoked exactly the way an operator's shell would, against a plaintext {@code target.yaml}
 * pointing at a real cluster. {@code preflight} reports it ready, {@code stress} deploys and scales
 * the bundled pause image to a passing gate with zero {@code gimle-examples} involvement, {@code
 * chaos} runs a network-fault-only plan without needing {@code --confirm-destructive}, and a plan
 * naming a destructive fault kind is correctly refused without it.
 */
@Tag("holmgang")
class RagnarokCliIT {

  @Test
  @Timeout(value = 10, unit = TimeUnit.MINUTES)
  void the_ragnarok_cli_drives_preflight_stress_and_chaos_against_a_real_cluster()
      throws Exception {
    final ClusterSpec spec = ClusterTopologyParser.fromClasspath("topologies/surtr-density.yaml");
    final Path workDir =
        Path.of("target", "holmgang", "ragnarok-cli-" + Long.toHexString(System.nanoTime()));
    final GimleCluster cluster = GimleCluster.start(spec, workDir);
    try {
      final Path targetFile = writeTargetFile(cluster, workDir);

      // preflight: everything configured is reachable.
      assertEquals(
          0, run("preflight", "--target", targetFile.toString()), "preflight should report ready");

      // stress: the bundled pause image, no gimle-examples build involved.
      assertEquals(
          0,
          run("stress", "--target", targetFile.toString(), "--workload", "pause-density"),
          "stress against the bundled pause image should pass its gates");

      // chaos: a network-fault-only plan needs no --confirm-destructive.
      final Path networkPlan = writePlanFile(workDir, "network-only.yaml", NETWORK_ONLY_PLAN);
      assertEquals(
          0,
          run("chaos", "--target", targetFile.toString(), "--plan", networkPlan.toString()),
          "a network-fault-only plan should be accepted and recover (vacuously, nothing fires)");

      // chaos: a destructive plan without --confirm-destructive is refused.
      final Path destructivePlan = writePlanFile(workDir, "destructive.yaml", DESTRUCTIVE_PLAN);
      final ByteArrayOutputStream err = new ByteArrayOutputStream();
      final int code =
          RagnarokMain.run(
              new String[] {
                "chaos", "--target", targetFile.toString(), "--plan", destructivePlan.toString()
              },
              new PrintStream(new ByteArrayOutputStream()),
              new PrintStream(err));
      assertEquals(1, code, "a destructive plan without --confirm-destructive must be refused");
      assertTrue(
          err.toString(StandardCharsets.UTF_8).contains("--confirm-destructive"),
          "the refusal message should name the missing flag: " + err);
    } finally {
      cluster.close();
    }
  }

  private static final String NETWORK_ONLY_PLAN =
      """
      soakSeconds: 5
      strikeEverySeconds: 1
      pools:
        - kind: LINK_CUT
        - kind: STORE_PARTITION
      """;

  private static final String DESTRUCTIVE_PLAN =
      """
      soakSeconds: 5
      strikeEverySeconds: 1
      eligibleDeployments: [does-not-matter]
      pools:
        - kind: WORKER_KILL
      """;

  private static int run(final String... args) {
    return RagnarokMain.run(
        args,
        new PrintStream(new ByteArrayOutputStream()),
        new PrintStream(new ByteArrayOutputStream()));
  }

  private static Path writeTargetFile(final GimleCluster cluster, final Path workDir)
      throws Exception {
    Files.createDirectories(workDir);
    final StringBuilder yaml = new StringBuilder();
    yaml.append("controlPlaneBaseUrls: [").append(cluster.controlPlaneBaseUrl(0)).append("]\n");
    yaml.append("storeClientEndpoints: [").append(storeEndpointsCsv(cluster)).append("]\n");
    final Path file = workDir.resolve("target.yaml");
    Files.writeString(file, yaml.toString());
    return file;
  }

  private static String storeEndpointsCsv(final GimleCluster cluster) {
    final List<SocketAddress> endpoints = cluster.storeClientEndpoints();
    final StringBuilder csv = new StringBuilder();
    for (int i = 0; i < endpoints.size(); i++) {
      if (i > 0) {
        csv.append(", ");
      }
      final InetSocketAddress address = (InetSocketAddress) endpoints.get(i);
      csv.append(address.getHostString()).append(':').append(address.getPort());
    }
    return csv.toString();
  }

  private static Path writePlanFile(final Path workDir, final String name, final String yaml)
      throws Exception {
    final Path file = workDir.resolve(name);
    Files.writeString(file, yaml);
    return file;
  }
}
