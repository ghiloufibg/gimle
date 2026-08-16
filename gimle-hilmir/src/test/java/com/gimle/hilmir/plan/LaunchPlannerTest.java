package com.gimle.hilmir.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.core.exception.GimleManifestException;
import com.gimle.hilmir.topology.ProcessRole;
import com.gimle.hilmir.topology.Topology;
import com.gimle.hilmir.topology.TopologyParser;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LaunchPlannerTest {

  @TempDir Path gimleHome;

  private static final ResolvedRuntime RUNTIME =
      new ResolvedRuntime("java", "test-classpath", Path.of("/data"));

  private static Topology parse(final String yaml) {
    final InputStream in = new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8));
    return TopologyParser.parse(in);
  }

  private static ProcessCommand only(
      final ClusterPlan plan, final String machine, final String id) {
    return plan.byMachine().get(machine).commands().stream()
        .filter(c -> c.id().equals(id))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no command " + id + " on machine " + machine));
  }

  @Test
  void plans_a_minimal_single_machine_plaintext_topology() {
    final Topology topology =
        parse(
            """
            name: minimal
            machines:
              - {name: m1, host: 127.0.0.1}
            store:
              replicas:
                - {machine: m1, raftPort: 9080, clientPort: 9091}
            controlPlane:
              replicas:
                - {machine: m1, port: 8080}
            fafnir:
              keyFile: /etc/gimle/fafnir-secret.key
              replicas:
                - {machine: m1, port: 9092}
            agents:
              - {machine: m1, nodeId: node-a, gossipPort: 9090}
            """);
    final ClusterPlan plan = LaunchPlanner.plan(topology, RUNTIME);

    assertEquals(
        List.of(
            "java",
            "-Dgimle.data.root=/data/store-0",
            "-Dgimle.log.root=/data/store-0-logs",
            "-cp",
            "test-classpath",
            "com.gimle.mimir.StoreMain",
            "/data/store-0",
            "9080",
            "9091",
            "--host",
            "127.0.0.1"),
        only(plan, "m1", "store-0").command());

    assertEquals(
        List.of(
            "java",
            "-Dgimle.data.root=/data/controlplane-0",
            "-Dgimle.log.root=/data/controlplane-0-logs",
            "-cp",
            "test-classpath",
            "com.gimle.controlplane.ControlPlaneMain",
            "8080",
            "/data/controlplane-0-secret.key",
            "--host",
            "127.0.0.1",
            "--store-endpoints",
            "127.0.0.1:9091",
            "--fafnir-endpoint",
            "127.0.0.1:9092"),
        only(plan, "m1", "controlplane-0").command());

    assertEquals(
        List.of(
            "java",
            "-Dgimle.data.root=/data/fafnir-0",
            "-Dgimle.log.root=/data/fafnir-0-logs",
            "-cp",
            "test-classpath",
            "com.gimle.fafnir.FafnirMain",
            "9092",
            "/etc/gimle/fafnir-secret.key",
            "--host",
            "127.0.0.1",
            "--store-endpoints",
            "127.0.0.1:9091"),
        only(plan, "m1", "fafnir-0").command());

    assertEquals(
        List.of(
            "java",
            "-Dgimle.agent.fafnirEndpoint=127.0.0.1:9092",
            "-Dgimle.data.root=/data/agent-node-a",
            "-Dgimle.log.root=/data/agent-node-a-logs",
            "-cp",
            "test-classpath",
            "com.gimle.agent.AgentMain",
            "node-a",
            "http://127.0.0.1:8080",
            "127.0.0.1:9090",
            "-",
            "java",
            "-cp",
            "test-classpath",
            "com.gimle.worker.WorkerMain"),
        only(plan, "m1", "agent-node-a").command());
    assertFalse(only(plan, "m1", "agent-node-a").needsBootstrapToken());

    assertEquals(1, plan.byMachine().size());
    assertEquals(4, plan.byMachine().get("m1").commands().size());
  }

  @Test
  void plans_a_multi_machine_plaintext_topology_with_correct_per_machine_filtering() {
    final Topology topology =
        parse(
            """
            name: spread
            machines:
              - {name: m1, host: h1}
              - {name: m2, host: h2}
            store:
              replicas:
                - {machine: m1, raftPort: 9080, clientPort: 9091}
                - {machine: m2, raftPort: 9080, clientPort: 9091}
            controlPlane:
              replicas:
                - {machine: m1, port: 8080}
            fafnir:
              keyFile: /key
              replicas:
                - {machine: m2, port: 9092}
            agents:
              - {machine: m1, nodeId: node-a, gossipPort: 9090}
              - {machine: m2, nodeId: node-b, gossipPort: 9090}
            """);
    final ClusterPlan plan = LaunchPlanner.plan(topology, RUNTIME);

    // Global boot order (stores, fafnir, control planes, agents) preserved per machine.
    assertEquals(
        List.of("store-0", "controlplane-0", "agent-node-a"),
        plan.byMachine().get("m1").commands().stream().map(ProcessCommand::id).toList());
    assertEquals(
        List.of("store-1", "fafnir-0", "agent-node-b"),
        plan.byMachine().get("m2").commands().stream().map(ProcessCommand::id).toList());

    // Each store replica peers with every *other* replica, addressed by that replica's own host.
    assertTrue(
        only(plan, "m1", "store-0").command().containsAll(List.of("--peers", "h2:9080:9091")));
    assertTrue(
        only(plan, "m2", "store-1").command().containsAll(List.of("--peers", "h1:9080:9091")));

    final ProcessCommand controlPlane = only(plan, "m1", "controlplane-0");
    assertTrue(controlPlane.command().containsAll(List.of("--store-endpoints", "h1:9091,h2:9091")));
    assertTrue(controlPlane.command().containsAll(List.of("--fafnir-endpoint", "h2:9092")));

    // Every agent registers with the first control-plane replica and seeds gossip off the first
    // agent's own address; the first agent itself seeds nothing.
    final ProcessCommand firstAgent = only(plan, "m1", "agent-node-a");
    final int firstAgentIndex = firstAgent.command().indexOf("http://h1:8080");
    assertTrue(firstAgentIndex >= 0);
    assertEquals("h1:9090", firstAgent.command().get(firstAgentIndex + 1));
    assertEquals("-", firstAgent.command().get(firstAgentIndex + 2));

    final ProcessCommand secondAgent = only(plan, "m2", "agent-node-b");
    final int secondAgentIndex = secondAgent.command().indexOf("http://h1:8080");
    assertTrue(secondAgentIndex >= 0);
    assertEquals("h2:9090", secondAgent.command().get(secondAgentIndex + 1));
    assertEquals("h1:9090", secondAgent.command().get(secondAgentIndex + 2));
  }

  @Test
  void plans_an_mtls_topology_with_tls_flags_and_bootstrap_token_marking() {
    final Topology topology =
        parse(
            """
            name: secure
            transport: mtls
            tls:
              materialDir: /etc/gimle/tls
            machines:
              - {name: m1, host: host1.example.com}
            store:
              replicas:
                - {machine: m1, raftPort: 9080, clientPort: 9091}
            controlPlane:
              replicas:
                - {machine: m1, port: 8080}
            fafnir:
              keyFile: /etc/gimle/fafnir-secret.key
              replicas:
                - {machine: m1, port: 9092}
            agents:
              - {machine: m1, nodeId: node-a, gossipPort: 9090}
            """);
    final ClusterPlan plan = LaunchPlanner.plan(topology, RUNTIME);

    final List<String> controlPlaneTlsFlags =
        List.of(
            "-Dgimle.transport.protocol=tls",
            "-Dgimle.tls.certFile=/etc/gimle/tls/controlplane.crt",
            "-Dgimle.tls.keyFile=/etc/gimle/tls/controlplane.key",
            "-Dgimle.tls.caFile=/etc/gimle/tls/ca.crt");
    assertTrue(only(plan, "m1", "store-0").command().containsAll(controlPlaneTlsFlags));

    final ProcessCommand controlPlane = only(plan, "m1", "controlplane-0");
    assertTrue(controlPlane.command().containsAll(controlPlaneTlsFlags));
    assertTrue(controlPlane.command().contains("-Dgimle.pki.caKeyFile=/etc/gimle/tls/ca.key"));

    final List<String> fafnirTlsFlags =
        List.of(
            "-Dgimle.transport.protocol=tls",
            "-Dgimle.tls.certFile=/etc/gimle/tls/fafnir.crt",
            "-Dgimle.tls.keyFile=/etc/gimle/tls/fafnir.key",
            "-Dgimle.tls.caFile=/etc/gimle/tls/ca.crt");
    assertTrue(only(plan, "m1", "fafnir-0").command().containsAll(fafnirTlsFlags));

    final ProcessCommand agent = only(plan, "m1", "agent-node-a");
    assertTrue(
        agent
            .command()
            .containsAll(
                List.of(
                    "-Dgimle.tls.certFile=/etc/gimle/tls/node-node-a.crt",
                    "-Dgimle.tls.keyFile=/etc/gimle/tls/node-node-a.key")));
    assertTrue(agent.needsBootstrapToken());
  }

  @Test
  void agents_do_not_need_a_bootstrap_token_under_plaintext() {
    final Topology topology =
        parse(
            """
            name: open
            machines:
              - {name: m1, host: 127.0.0.1}
            store:
              replicas:
                - {machine: m1}
            controlPlane:
              replicas:
                - {machine: m1}
            fafnir:
              keyFile: /key
              replicas:
                - {machine: m1}
            agents:
              - {machine: m1, nodeId: node-a}
            """);
    final ClusterPlan plan = LaunchPlanner.plan(topology, RUNTIME);
    assertFalse(only(plan, "m1", "agent-node-a").needsBootstrapToken());
  }

  @Test
  void never_emits_an_empty_peers_value_for_any_replica_when_the_store_has_more_than_one() {
    final Topology topology =
        parse(
            """
            name: quorum
            machines:
              - {name: m1, host: h1}
              - {name: m2, host: h2}
              - {name: m3, host: h3}
            store:
              replicas:
                - {machine: m1, raftPort: 9080, clientPort: 9091}
                - {machine: m2, raftPort: 9080, clientPort: 9091}
                - {machine: m3, raftPort: 9080, clientPort: 9091}
            controlPlane:
              replicas:
                - {machine: m1}
            fafnir:
              keyFile: /key
              replicas:
                - {machine: m1}
            agents:
              - {machine: m1, nodeId: node-a}
            """);
    final ClusterPlan plan = LaunchPlanner.plan(topology, RUNTIME);
    for (final String machine : List.of("m1", "m2", "m3")) {
      final ProcessCommand store =
          plan.byMachine().get(machine).commands().stream()
              .filter(c -> c.role() == ProcessRole.STORE)
              .findFirst()
              .orElseThrow();
      final int peersIndex = store.command().indexOf("--peers");
      assertTrue(peersIndex >= 0, "expected --peers on " + store.id());
      assertFalse(store.command().get(peersIndex + 1).isBlank());
    }
  }

  @Test
  void a_single_store_replica_gets_no_peers_flag_at_all() {
    final Topology topology =
        parse(
            """
            name: single
            machines:
              - {name: m1, host: 127.0.0.1}
            store:
              replicas:
                - {machine: m1}
            controlPlane:
              replicas:
                - {machine: m1}
            fafnir:
              keyFile: /key
              replicas:
                - {machine: m1}
            """);
    final ClusterPlan plan = LaunchPlanner.plan(topology, RUNTIME);
    assertFalse(only(plan, "m1", "store-0").command().contains("--peers"));
  }

  @Test
  void muninn_and_andvari_flags_are_only_emitted_when_those_roles_have_replicas() {
    final Topology withNeither =
        parse(
            """
            name: bare
            machines:
              - {name: m1, host: 127.0.0.1}
            store:
              replicas:
                - {machine: m1}
            controlPlane:
              replicas:
                - {machine: m1}
            fafnir:
              keyFile: /key
              replicas:
                - {machine: m1}
            agents:
              - {machine: m1, nodeId: node-a}
            """);
    final ClusterPlan bare = LaunchPlanner.plan(withNeither, RUNTIME);
    assertFalse(
        only(bare, "m1", "controlplane-0").command().stream()
            .anyMatch(arg -> arg.contains("muninn") || arg.contains("andvari")));
    assertFalse(
        only(bare, "m1", "agent-node-a").command().stream()
            .anyMatch(arg -> arg.contains("muninnEndpoint") || arg.contains("andvariEndpoint")));

    final Topology withBoth =
        parse(
            """
            name: observed
            machines:
              - {name: m1, host: 127.0.0.1}
            store:
              replicas:
                - {machine: m1}
            controlPlane:
              replicas:
                - {machine: m1}
            fafnir:
              keyFile: /key
              replicas:
                - {machine: m1}
            muninn:
              replicas:
                - {machine: m1, port: 9093}
            andvari:
              replicas:
                - {machine: m1, port: 9094}
            agents:
              - {machine: m1, nodeId: node-a}
            """);
    final ClusterPlan observed = LaunchPlanner.plan(withBoth, RUNTIME);
    assertTrue(
        only(observed, "m1", "controlplane-0")
            .command()
            .containsAll(
                List.of(
                    "--muninn-endpoint",
                    "127.0.0.1:9093",
                    "--andvari-endpoint",
                    "127.0.0.1:9094")));
    assertTrue(
        only(observed, "m1", "agent-node-a")
            .command()
            .contains("-Dgimle.agent.muninnEndpoint=127.0.0.1:9093"));
    assertTrue(
        only(observed, "m1", "agent-node-a")
            .command()
            .contains("-Dgimle.agent.andvariEndpoint=127.0.0.1:9094"));
  }

  /**
   * The load-bearing safety-boundary regression test: {@code planAgents}' own command (including
   * the {@code WORKER} command tail it appends for {@code AgentMain} to spawn) never changes based
   * on {@code runtime.useBundledJre}. {@code planAgents} unconditionally dereferences both {@code
   * fafnir} and {@code controlPlane}, so both sections are declared here (with a bundled-JRE
   * fixture for each, since {@code useBundledJre} applies uniformly to every role present, not just
   * the one under test) -- but deliberately no store/muninn/andvari section, so this assertion
   * can't accidentally pass only because those other roles' own bundled-JRE resolution happened to
   * succeed too.
   */
  @Test
  void plan_agents_command_never_changes_based_on_use_bundled_jre() throws IOException {
    createFakeJavaBinary("fafnir");
    createFakeJavaBinary("controlplane");
    final String agentTopology =
        """
        name: agent-only
        machines:
          - {name: m1, host: 127.0.0.1}
        controlPlane:
          replicas:
            - {machine: m1}
        fafnir:
          keyFile: /key
          replicas:
            - {machine: m1}
        agents:
          - {machine: m1, nodeId: node-a, gossipPort: 9090}
        %s
        """;
    final Topology withoutBundledJre = parse(String.format(agentTopology, ""));
    final Topology withBundledJre =
        parse(String.format(agentTopology, "runtime:\n  useBundledJre: true\n"));

    final ClusterPlan planWithout =
        LaunchPlanner.plan(withoutBundledJre, RUNTIME, gimleHome.toString());
    final ClusterPlan planWith = LaunchPlanner.plan(withBundledJre, RUNTIME, gimleHome.toString());

    final List<String> agentCommandWithout = only(planWithout, "m1", "agent-node-a").command();
    final List<String> agentCommandWith = only(planWith, "m1", "agent-node-a").command();
    assertEquals(agentCommandWithout, agentCommandWith);
    assertEquals("java", agentCommandWith.get(0));
    // The WORKER command tail AgentMain spawns is the second "java", five tokens after
    // "com.gimle.agent.AgentMain" itself (AgentMain, nodeId, controlPlaneBaseUrl, gossipAddress,
    // seeds, then the worker's own java).
    assertEquals(
        "java", agentCommandWith.get(agentCommandWith.indexOf("com.gimle.agent.AgentMain") + 5));
    // And confirm the control plane / fafnir commands in this same plan DID pick up the bundled
    // path, so this test isn't accidentally passing because useBundledJre had no effect at all.
    assertEquals(
        bundledJavaPath("controlplane").toString(),
        only(planWith, "m1", "controlplane-0").command().get(0));
    assertEquals(
        bundledJavaPath("fafnir").toString(), only(planWith, "m1", "fafnir-0").command().get(0));
  }

  @Test
  void
      stores_muninn_andvari_fafnir_and_control_planes_resolve_their_own_bundled_java_when_use_bundled_jre_is_true()
          throws IOException {
    createFakeJavaBinary("mimir");
    createFakeJavaBinary("muninn");
    createFakeJavaBinary("andvari");
    createFakeJavaBinary("fafnir");
    createFakeJavaBinary("controlplane");

    final Topology topology =
        parse(
            """
            name: bundled
            runtime:
              useBundledJre: true
            machines:
              - {name: m1, host: 127.0.0.1}
            store:
              replicas:
                - {machine: m1}
            controlPlane:
              replicas:
                - {machine: m1}
            fafnir:
              keyFile: /key
              replicas:
                - {machine: m1}
            muninn:
              replicas:
                - {machine: m1}
            andvari:
              replicas:
                - {machine: m1}
            agents:
              - {machine: m1, nodeId: node-a}
            """);
    final ClusterPlan plan = LaunchPlanner.plan(topology, RUNTIME, gimleHome.toString());

    assertEquals(bundledJavaPath("mimir").toString(), only(plan, "m1", "store-0").command().get(0));
    assertEquals(
        bundledJavaPath("muninn").toString(), only(plan, "m1", "muninn-0").command().get(0));
    assertEquals(
        bundledJavaPath("andvari").toString(), only(plan, "m1", "andvari-0").command().get(0));
    assertEquals(
        bundledJavaPath("fafnir").toString(), only(plan, "m1", "fafnir-0").command().get(0));
    assertEquals(
        bundledJavaPath("controlplane").toString(),
        only(plan, "m1", "controlplane-0").command().get(0));
    // The safety boundary holds even inside this same bundled-JRE-enabled plan: the agent (and the
    // worker command tail it appends) still gets the plain runtime.javaExecutable(), never a
    // resolved bundled path -- no jre/agent or jre/worker fixture was created above at all.
    final List<String> agentCommand = only(plan, "m1", "agent-node-a").command();
    assertEquals("java", agentCommand.get(0));
    assertEquals("java", agentCommand.get(agentCommand.indexOf("com.gimle.agent.AgentMain") + 5));
  }

  @Test
  void fails_clearly_when_use_bundled_jre_is_true_and_a_components_bundled_java_is_missing() {
    final Topology topology =
        parse(
            """
            name: bundled-missing
            runtime:
              useBundledJre: true
            machines:
              - {name: m1, host: 127.0.0.1}
            store:
              replicas:
                - {machine: m1}
            """);
    final GimleManifestException e =
        assertThrows(
            GimleManifestException.class,
            () -> LaunchPlanner.plan(topology, RUNTIME, gimleHome.toString()));
    assertTrue(e.getMessage().contains("mimir"));
  }

  private void createFakeJavaBinary(final String component) throws IOException {
    final Path javaBin = bundledJavaPath(component);
    Files.createDirectories(javaBin.getParent());
    Files.createFile(javaBin);
  }

  private Path bundledJavaPath(final String component) {
    return gimleHome.resolve("jre").resolve(component).resolve("bin").resolve("java");
  }
}
