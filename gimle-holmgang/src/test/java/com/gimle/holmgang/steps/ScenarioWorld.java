package com.gimle.holmgang.steps;

import com.gimle.holmgang.HolmgangException;
import com.gimle.holmgang.cluster.ClusterApi;
import com.gimle.holmgang.cluster.GimleCluster;
import com.gimle.holmgang.workload.RecordingWorkload;
import com.gimle.ragnarok.fenrir.ChaosLedger;
import com.gimle.ragnarok.target.NetworkFaultInjector;
import com.gimle.testkit.heimdall.InvariantGuard;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.X509Certificate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * The one mutable state shared by every step class within a scenario (constructor-injected by
 * cucumber-picocontainer, fresh per scenario). Everything a scenario creates is recorded here so
 * the after-scenario hook can hand a pooled cluster back exactly as it found it.
 */
public final class ScenarioWorld {

  /** What a scenario deployed, remembered so a rolling-update step can reuse its shape. */
  public record DeployedModule(
      String moduleName, String version, int replicas, Optional<String> tenantId) {}

  final Map<String, DeployedModule> deployments = new LinkedHashMap<>();

  /**
   * A created NetworkPolicy's own {@code name -> tenantId}, remembered so a later step can address
   * it via {@code ?tenant=} without the Gherkin sentence itself needing to repeat the tenant every
   * time it references the policy by name.
   */
  final Map<String, String> networkPolicyTenants = new LinkedHashMap<>();

  /** One custom resource a scenario applied, remembered so cleanup can delete it. */
  public record AppliedCustomResource(String kindName, String name, Optional<String> tenantId) {}

  /** Instances first, then definitions -- a definition with live instances refuses deletion. */
  final List<AppliedCustomResource> customResources = new ArrayList<>();

  final List<String> kindDefinitions = new ArrayList<>();

  final List<String> statefulSets = new ArrayList<>();
  final List<String> tenants = new ArrayList<>();
  final List<String> cordonedNodes = new ArrayList<>();
  final Deque<InvariantGuard> guards = new ArrayDeque<>();
  final Deque<NetworkFaultInjector.Partition> partitions = new ArrayDeque<>();
  final List<Process> loadProcesses = new ArrayList<>();
  final Map<String, Long> workerPids = new HashMap<>();
  Integer lastSubmissionStatus;
  ClusterApi.LoginResult lastLogin;
  RecordingWorkload workload;
  ChaosLedger chaosLedger;
  String scenarioName = "scenario";

  /** Active key ids returned by each {@code secrets key is rotated} step, in rotation order. */
  final List<Integer> rotatedSecretsKeyIds = new ArrayList<>();

  /** The status of the most recent Fafnir {@code /auth/login} attempt. */
  Integer lastFafnirAuthStatus;

  /**
   * The raw {@code name=value} pair carved out of a successful Fafnir login's own {@code
   * Set-Cookie} response header, reattached by hand on every later {@code /auth/*} request in the
   * scenario -- {@code java.net.http.HttpClient}'s own automatic {@link CookieManager} integration
   * does not reliably round-trip a {@code SameSite=Strict} cookie the way a real browser does, the
   * same reason {@code FafnirServerAuthTest} manages this cookie by hand rather than relying on it.
   */
  String fafnirSessionCookie;

  /** The status of the most recent direct {@code POST /bootstrap/csr} submission. */
  Integer lastCsrSubmissionStatus;

  /** The certificate a CSR submission most recently got back, once approved. */
  X509Certificate lastIssuedCertificate;

  /** A node's own certificate, captured just before this scenario rotated it. */
  X509Certificate originalNodeCertificate;

  /** A write submitted on a background thread, so a step can bound its wait instead of hanging. */
  CompletableFuture<Integer> pendingWrite;

  /**
   * Which store index a partition step isolated, remembered since the cluster's leader moves on.
   */
  Integer isolatedStoreIndex;

  /** The tenant id a ghost-write step proposed directly to an isolated leader, and its outcome. */
  String ghostWriteTenantId;

  CompletableFuture<Void> ghostWriteOutcome;

  /** Whether the most recent lease acquire/renew attempt was granted. */
  Boolean lastLeaseGranted;

  /** The node id a StatefulSet sticky-binding scenario captured before a reschedule. */
  String rememberedNodeId;

  private GimleCluster cluster;
  private boolean destructive;
  private boolean ownsCluster;
  private Path ownedWorkDir;
  private Path fixturesDir;

  void markDestructive() {
    destructive = true;
  }

  boolean isDestructive() {
    return destructive;
  }

  void attachPooled(final GimleCluster pooled) {
    this.cluster = pooled;
    this.ownsCluster = false;
  }

  void attachOwned(final GimleCluster owned, final Path workDir) {
    this.cluster = owned;
    this.ownsCluster = true;
    this.ownedWorkDir = workDir;
  }

  boolean hasCluster() {
    return cluster != null;
  }

  boolean ownsCluster() {
    return ownsCluster;
  }

  Path ownedWorkDir() {
    return ownedWorkDir;
  }

  GimleCluster cluster() {
    if (cluster == null) {
      throw new HolmgangException(
          "no cluster in this scenario -- start with: Given a running cluster from topology"
              + " \"<name>\"");
    }
    return cluster;
  }

  /** Where this scenario's runtime-compiled module jars land; created on first use. */
  Path fixturesDir() {
    if (fixturesDir == null) {
      fixturesDir = Path.of("target", "holmgang-fixtures", Long.toHexString(System.nanoTime()));
      try {
        Files.createDirectories(fixturesDir);
      } catch (final IOException e) {
        throw new HolmgangException("failed creating fixtures directory " + fixturesDir, e);
      }
    }
    return fixturesDir;
  }
}
