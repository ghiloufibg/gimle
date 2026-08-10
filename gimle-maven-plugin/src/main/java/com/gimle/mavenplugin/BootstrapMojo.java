package com.gimle.mavenplugin;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.repository.RemoteRepository;

/**
 * {@code mvn gimle:bootstrap [-Dgimle.bootstrap.protocol=plaintext|tls]} -- collapses the
 * multi-terminal local-dev walkthrough ({@code gimle-console/LOCAL_DEV.md}: {@code tls-init} if
 * TLS, {@code store}, {@code controlplane}, {@code agent}, then a {@code cert token create} +
 * {@code apply} per manifest if TLS) into one foreground command: brings up a single-node store +
 * control plane + agent (with its own worker child, same as {@code gimle:agent} today), deploys
 * every {@code gimle-examples} module once the cluster is ready, prints a summary, then blocks
 * until interrupted and tears the whole cluster back down.
 *
 * <p>Unlike every other goal here, this doesn't map to one reactor module: it needs eight modules'
 * runtime classpaths (store, fafnir, muninn, control plane, agent, worker, pki, cli), not one, and
 * supervises four long-running processes together rather than one. So it self-filters to the root
 * aggregator project (artifactId {@code "gimle"}, guaranteed present regardless of {@code -pl}),
 * the same pattern {@link DocsMojo} already uses, instead of extending {@link AbstractGimleMojo}.
 *
 * <p>Reuses the exact port/host defaults {@link StoreMojo}/{@link FafnirMojo}/{@link
 * MuninnMojo}/{@link ControlPlaneMojo}/{@link AgentMojo} already use, deliberately: this goal and
 * "those goals run by hand in separate terminals" are meant to be the same cluster, not two
 * topologies to keep in sync -- which also means this goal isn't meant to run alongside an
 * already-running manual session of any of them.
 *
 * <p>TLS-mode caveat, worth recording rather than hiding: {@code gimle-pki}'s {@code
 * PkiBootstrapMain} mints {@code controlplane}, {@code fafnir}, and {@code operator} leaf
 * certificates -- there is no dedicated {@code store} identity. The store node here is given the
 * same {@code controlplane} leaf certificate as its own TLS identity; safe because the store's own
 * transports (Raft peer RPC, the client-facing store RPC) are raw {@code SSLSocket}s verified only
 * against the shared CA, not hostname/SAN-checked the way {@code ApiServer}'s HTTPS surface is, and
 * because a single-node bootstrap never opens a Raft peer connection at all (zero peers). Fafnir
 * does *not* share this stand-in -- it gets its own distinct {@code fafnir} leaf, since every
 * action it takes being attributable to its own certificate Subject is directly load-bearing for
 * its audit story (design doc §6f), unlike the store's own Raft/client RPC transports. A real
 * multi-node TLS deployment would need {@code gimle-pki} to mint a real per-store-node identity too
 * -- out of scope here. Also out of scope: propagating {@code gimle.tls.*} into the worker JVM this
 * goal's agent spawns, needed only for genuine cross-machine fabric TLS (same-machine fabric is a
 * Unix domain socket, never TLS'd), which a single-machine bootstrap never exercises.
 */
@Mojo(name = "bootstrap", threadSafe = true)
public final class BootstrapMojo extends AbstractMojo {

  private static final int STORE_RAFT_PORT = 9080;
  private static final int STORE_CLIENT_PORT = 9091;
  // Matches FafnirMojo's own gimle.fafnir.port default.
  private static final int FAFNIR_PORT = 9092;
  // Matches MuninnMojo's own gimle.muninn.port default.
  private static final int MUNINN_PORT = 9093;
  private static final int CONTROLPLANE_PORT = 8080;
  private static final String AGENT_NODE_ID = "node-1";
  private static final String GOSSIP_BIND_ADDRESS = "127.0.0.1:9090";
  private static final String CA_COMMON_NAME = "gimle-cluster-ca";
  // Must match a SAN PkiBootstrapMain actually issues (its own javadoc: a bare IP literal fails
  // hostname verification even with a valid chain) -- every TLS-mode client here (this goal's own
  // readiness polling excepted, which is a bare TCP connect) talks to the control plane by this
  // name, never by 127.0.0.1.
  private static final String TLS_HOSTNAME = "localhost";

  private record ExampleModule(
      String deploymentName, String manifestPath, String jarPathTemplate) {}

  private static final List<ExampleModule> EXAMPLES =
      List.of(
          new ExampleModule(
              "hello-deployment",
              "gimle-examples/hello-module/deployment.yaml",
              "gimle-examples/hello-module/target/hello-module-%s.jar"),
          new ExampleModule(
              "greeter-provider-deployment",
              "gimle-examples/greeter-provider/deployment.yaml",
              "gimle-examples/greeter-provider/target/greeter-provider-%s.jar"),
          new ExampleModule(
              "greeter-consumer-deployment",
              "gimle-examples/greeter-consumer/deployment.yaml",
              "gimle-examples/greeter-consumer/target/greeter-consumer-%s.jar"));

  @Parameter(defaultValue = "${project}", readonly = true, required = true)
  private MavenProject project;

  @Parameter(property = "gimle.bootstrap.protocol", defaultValue = "plaintext")
  private String protocol;

  @Parameter(
      property = "gimle.bootstrap.baseDir",
      defaultValue = "${project.basedir}/gimle-bootstrap")
  private String baseDir;

  @Parameter(property = "gimle.bootstrap.deployExamples", defaultValue = "true")
  private boolean deployExamples;

  @Parameter(property = "gimle.bootstrap.readyTimeoutSeconds", defaultValue = "120")
  private long readyTimeoutSeconds;

  @Parameter(defaultValue = "${project.version}", readonly = true, required = true)
  private String projectVersion;

  @Parameter(
      defaultValue = "${project.remoteProjectRepositories}",
      readonly = true,
      required = true)
  private List<RemoteRepository> remoteRepositories;

  @Parameter(defaultValue = "${repositorySystemSession}", readonly = true, required = true)
  private RepositorySystemSession repositorySystemSession;

  @Component private RepositorySystem repositorySystem;

  @Override
  public void execute() throws MojoExecutionException, MojoFailureException {
    if (!"gimle".equals(project.getArtifactId())) {
      getLog().debug("skipping bootstrap: not the root aggregator project");
      return;
    }
    boolean tls = parseProtocol();
    Path base = Path.of(baseDir).toAbsolutePath();
    Path logsDir = base.resolve("logs");
    Path tlsDir = base.resolve("tls");
    createDirectory(logsDir);

    if (deployExamples) {
      verifyExampleJarsExist();
    }
    if (tls) {
      createDirectory(tlsDir);
      runTlsInit(tlsDir);
    }

    Duration readyTimeout = Duration.ofSeconds(readyTimeoutSeconds);
    String controlPlaneHost = tls ? TLS_HOSTNAME : "127.0.0.1";

    List<Process> spawned = new ArrayList<>();
    Thread shutdownHook = new Thread(() -> shutdownAll(spawned), "gimle-bootstrap-shutdown");
    Runtime.getRuntime().addShutdownHook(shutdownHook);
    try {
      spawned.add(spawnStore(base, tls, tlsDir, logsDir));
      awaitTrue(
          () -> isPortOpen(STORE_CLIENT_PORT),
          readyTimeout,
          "store client port " + STORE_CLIENT_PORT + " should start listening");

      // Before fafnir/control-plane, not after: Muninn only needs the store (its own read-only
      // Authorizer check), so bringing it up this early means it's already reachable to receive
      // shipped data from every process started after it, from as early in the cluster's lifecycle
      // as possible.
      spawned.add(spawnMuninn(base, tls, tlsDir, logsDir));
      awaitTrue(
          () -> isPortOpen(MUNINN_PORT),
          readyTimeout,
          "muninn port " + MUNINN_PORT + " should start listening");

      spawned.add(spawnFafnir(base, tls, tlsDir, logsDir));
      awaitTrue(
          () -> isPortOpen(FAFNIR_PORT),
          readyTimeout,
          "fafnir port " + FAFNIR_PORT + " should start listening");

      spawned.add(spawnControlPlane(base, tls, tlsDir, logsDir));
      awaitTrue(
          () -> isPortOpen(CONTROLPLANE_PORT),
          readyTimeout,
          "control-plane port " + CONTROLPLANE_PORT + " should start listening");

      String bootstrapToken = tls ? mintBootstrapToken(tlsDir, controlPlaneHost) : null;

      spawned.add(spawnAgent(tls, tlsDir, controlPlaneHost, bootstrapToken, logsDir));
      String cliClasspath = resolveClasspath("gimle-cli");
      awaitTrue(
          () -> hasRegisteredNodes(cliClasspath, tls, tlsDir, controlPlaneHost),
          readyTimeout,
          "the agent should register a node with the control plane");

      if (deployExamples) {
        applyExamples(cliClasspath, tls, tlsDir, controlPlaneHost);
        awaitExamplesActive(cliClasspath, tls, tlsDir, controlPlaneHost, readyTimeout);
      }

      printSummary(tls, controlPlaneHost, base);
      getLog().info("gimle cluster running -- press Ctrl+C to stop it");
      new CountDownLatch(1).await();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } finally {
      try {
        Runtime.getRuntime().removeShutdownHook(shutdownHook);
      } catch (IllegalStateException ignored) {
        // The JVM is already shutting down -- the hook either already ran or is redundant now.
      }
      shutdownAll(spawned);
    }
  }

  private boolean parseProtocol() throws MojoExecutionException {
    return switch (protocol) {
      case "plaintext" -> false;
      case "tls" -> true;
      default ->
          throw new MojoExecutionException(
              "gimle.bootstrap.protocol must be 'plaintext' or 'tls', got: " + protocol);
    };
  }

  private void createDirectory(Path dir) throws MojoExecutionException {
    try {
      Files.createDirectories(dir);
    } catch (IOException e) {
      throw new MojoExecutionException("failed to create " + dir, e);
    }
  }

  private void verifyExampleJarsExist() throws MojoExecutionException {
    for (ExampleModule example : EXAMPLES) {
      Path jar = repoRoot().resolve(example.jarPathTemplate().formatted(projectVersion));
      if (!Files.isRegularFile(jar)) {
        throw new MojoExecutionException(
            "expected a built example jar at " + jar + " -- run `mvn install` first");
      }
    }
  }

  private Path repoRoot() {
    return project.getBasedir().toPath();
  }

  private String resolveClasspath(String artifactId) throws MojoExecutionException {
    return GimleProcesses.resolveRuntimeClasspath(
        artifactId, projectVersion, remoteRepositories, repositorySystemSession, repositorySystem);
  }

  // ---- process spawning ----

  private void runTlsInit(Path tlsDir) throws MojoExecutionException, MojoFailureException {
    List<String> command = new ArrayList<>();
    command.add(GimleProcesses.javaExecutable());
    command.add("-cp");
    command.add(resolveClasspath("gimle-pki"));
    command.add("com.gimle.pki.PkiBootstrapMain");
    command.add(tlsDir.toString());
    command.add(CA_COMMON_NAME);
    command.add(TLS_HOSTNAME);
    getLog().info("generating cluster CA and leaf certificates in " + tlsDir);
    runToCompletion(command);
  }

  private Process spawnStore(Path base, boolean tls, Path tlsDir, Path logsDir)
      throws MojoExecutionException {
    List<String> command = new ArrayList<>();
    command.add(GimleProcesses.javaExecutable());
    if (tls) {
      // See the class javadoc: reuses the controlplane leaf certificate, there is no dedicated
      // store identity yet.
      addTlsFlags(
          command,
          tlsDir.resolve("controlplane.crt"),
          tlsDir.resolve("controlplane.key"),
          tlsDir.resolve("ca.crt"));
    }
    command.add("-cp");
    command.add(resolveClasspath("gimle-mimir"));
    command.add("com.gimle.mimir.StoreMain");
    command.add(base.resolve("store-state").toString());
    command.add(String.valueOf(STORE_RAFT_PORT));
    command.add(String.valueOf(STORE_CLIENT_PORT));
    getLog().info("starting store on client port " + STORE_CLIENT_PORT);
    return spawnLongRunning(command, logsDir.resolve("store.log"));
  }

  private Process spawnFafnir(Path base, boolean tls, Path tlsDir, Path logsDir)
      throws MojoExecutionException {
    List<String> command = new ArrayList<>();
    command.add(GimleProcesses.javaExecutable());
    if (tls) {
      // Unlike the store above, Fafnir gets its own distinct leaf identity from cluster-bootstrap
      // time -- see the class javadoc for why sharing the control plane's borrowed identity, the
      // store's own stand-in, isn't an option here.
      addTlsFlags(
          command,
          tlsDir.resolve("fafnir.crt"),
          tlsDir.resolve("fafnir.key"),
          tlsDir.resolve("ca.crt"));
    }
    command.add("-cp");
    command.add(resolveClasspath("gimle-fafnir"));
    command.add("com.gimle.fafnir.FafnirMain");
    command.add(String.valueOf(FAFNIR_PORT));
    command.add(base.resolve("fafnir-secret.key").toString());
    command.add("--store-endpoints");
    command.add("127.0.0.1:" + STORE_CLIENT_PORT);
    getLog().info("starting fafnir on port " + FAFNIR_PORT);
    return spawnLongRunning(command, logsDir.resolve("fafnir.log"));
  }

  private Process spawnMuninn(Path base, boolean tls, Path tlsDir, Path logsDir)
      throws MojoExecutionException {
    List<String> command = new ArrayList<>();
    command.add(GimleProcesses.javaExecutable());
    if (tls) {
      // Its own dedicated leaf identity, the same reasoning as Fafnir's above: Muninn's own
      // independent Authorizer check on proxied reads needs to be attributable to its own
      // certificate Subject, not a borrowed one.
      addTlsFlags(
          command,
          tlsDir.resolve("muninn.crt"),
          tlsDir.resolve("muninn.key"),
          tlsDir.resolve("ca.crt"));
    }
    command.add("-cp");
    command.add(resolveClasspath("gimle-muninn"));
    command.add("com.gimle.muninn.MuninnMain");
    command.add(String.valueOf(MUNINN_PORT));
    command.add("--store-endpoints");
    command.add("127.0.0.1:" + STORE_CLIENT_PORT);
    command.add("--data-root");
    command.add(base.resolve("muninn-data").toString());
    getLog().info("starting muninn on port " + MUNINN_PORT);
    return spawnLongRunning(command, logsDir.resolve("muninn.log"));
  }

  private Process spawnControlPlane(Path base, boolean tls, Path tlsDir, Path logsDir)
      throws MojoExecutionException {
    List<String> command = new ArrayList<>();
    command.add(GimleProcesses.javaExecutable());
    if (tls) {
      addTlsFlags(
          command,
          tlsDir.resolve("controlplane.crt"),
          tlsDir.resolve("controlplane.key"),
          tlsDir.resolve("ca.crt"));
      // Distinct from gimle.tls.keyFile (this node's own leaf key): the cluster CA's own private
      // key, needed so this control plane can sign incoming CSRs and mint bootstrap tokens at
      // /bootstrap/csr and /bootstrap/tokens (see CaKeyMaterial's own javadoc). A single-node
      // bootstrap has exactly one control-plane replica, so it's always the one holding
      // cluster-signing authority here.
      command.add("-Dgimle.pki.caKeyFile=" + tlsDir.resolve("ca.key"));
    }
    command.add("-cp");
    command.add(resolveClasspath("gimle-controlplane"));
    command.add("com.gimle.controlplane.ControlPlaneMain");
    command.add(String.valueOf(CONTROLPLANE_PORT));
    command.add(base.resolve("controlplane-secret.key").toString());
    command.add("--store-endpoints");
    command.add("127.0.0.1:" + STORE_CLIENT_PORT);
    command.add("--fafnir-endpoint");
    command.add("127.0.0.1:" + FAFNIR_PORT);
    // Optional (design doc Part B/O-11) -- lets this replica's /logs/* proxy fall back to
    // Muninn's own shipped history for a gone node/instance instead of a bare 404/502.
    command.add("--muninn-endpoint");
    command.add("127.0.0.1:" + MUNINN_PORT);
    getLog().info("starting control plane on port " + CONTROLPLANE_PORT);
    return spawnLongRunning(command, logsDir.resolve("controlplane.log"));
  }

  private Process spawnAgent(
      boolean tls, Path tlsDir, String controlPlaneHost, String bootstrapToken, Path logsDir)
      throws MojoExecutionException {
    String workerClasspath = resolveClasspath("gimle-worker");
    String controlPlaneUrl =
        (tls ? "https" : "http") + "://" + controlPlaneHost + ":" + CONTROLPLANE_PORT;

    List<String> command = new ArrayList<>();
    command.add(GimleProcesses.javaExecutable());
    if (tls) {
      addTlsFlags(
          command,
          tlsDir.resolve("agent.crt"),
          tlsDir.resolve("agent.key"),
          tlsDir.resolve("ca.crt"));
      command.add("-Dgimle.tls.bootstrapToken=" + bootstrapToken);
    }
    // Lets this agent fetch secret values straight from Fafnir (design doc §9/§11 Phase C)
    // instead of relying on the control plane to have already decrypted them -- see AgentMain's
    // own javadoc on gimle.agent.fafnirEndpoint for why this is a system property rather than a
    // new positional arg.
    command.add("-Dgimle.agent.fafnirEndpoint=127.0.0.1:" + FAFNIR_PORT);
    // Same reasoning, for shipping this agent's own + every supervised worker's logs to Muninn
    // (design doc Part B) -- see AgentMain's own javadoc on gimle.agent.muninnEndpoint.
    command.add("-Dgimle.agent.muninnEndpoint=127.0.0.1:" + MUNINN_PORT);
    command.add("-cp");
    command.add(resolveClasspath("gimle-agent"));
    command.add("com.gimle.agent.AgentMain");
    command.add(AGENT_NODE_ID);
    command.add(controlPlaneUrl);
    command.add(GOSSIP_BIND_ADDRESS);
    command.add("-");
    command.add(GimleProcesses.javaExecutable());
    command.add("-cp");
    command.add(workerClasspath);
    command.add("com.gimle.worker.WorkerMain");
    getLog().info("starting agent " + AGENT_NODE_ID + " against " + controlPlaneUrl);
    return spawnLongRunning(command, logsDir.resolve("agent.log"));
  }

  private static void addTlsFlags(List<String> command, Path certFile, Path keyFile, Path caFile) {
    command.add("-Dgimle.transport.protocol=tls");
    command.add("-Dgimle.tls.certFile=" + certFile);
    command.add("-Dgimle.tls.keyFile=" + keyFile);
    command.add("-Dgimle.tls.caFile=" + caFile);
  }

  private Process spawnLongRunning(List<String> command, Path logFile)
      throws MojoExecutionException {
    ProcessBuilder builder =
        new ProcessBuilder(command)
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.to(logFile.toFile()));
    try {
      return builder.start();
    } catch (IOException e) {
      throw new MojoExecutionException("failed to start " + command.get(0), e);
    }
  }

  // ---- CLI helpers (bootstrap token, node registration, apply, ACTIVE polling) ----

  private List<String> cliCommand(
      String cliClasspath, boolean tls, Path tlsDir, String controlPlaneHost, String... args) {
    List<String> command = new ArrayList<>();
    command.add(GimleProcesses.javaExecutable());
    if (tls) {
      addTlsFlags(
          command,
          tlsDir.resolve("operator.crt"),
          tlsDir.resolve("operator.key"),
          tlsDir.resolve("ca.crt"));
    }
    command.add("-cp");
    command.add(cliClasspath);
    command.add("com.gimle.cli.GimleCli");
    command.addAll(List.of(args));
    command.add("--server");
    command.add(controlPlaneHost + ":" + CONTROLPLANE_PORT);
    return command;
  }

  private String mintBootstrapToken(Path tlsDir, String controlPlaneHost)
      throws MojoExecutionException, MojoFailureException {
    String cliClasspath = resolveClasspath("gimle-cli");
    List<String> command =
        cliCommand(cliClasspath, true, tlsDir, controlPlaneHost, "cert", "token", "create");
    getLog().info("minting a node bootstrap token");
    String output = runCapturing(command);
    Matcher matcher = Pattern.compile("bootstrap token: (\\S+)").matcher(output);
    if (!matcher.find()) {
      throw new MojoExecutionException(
          "could not find a bootstrap token in CLI output:\n" + output);
    }
    return matcher.group(1);
  }

  private boolean hasRegisteredNodes(
      String cliClasspath, boolean tls, Path tlsDir, String controlPlaneHost) {
    List<String> command =
        cliCommand(cliClasspath, tls, tlsDir, controlPlaneHost, "get", "nodes", "-o", "json");
    try {
      return !runCapturing(command).strip().equals("[]");
    } catch (MojoExecutionException | MojoFailureException e) {
      return false;
    }
  }

  private void applyExamples(String cliClasspath, boolean tls, Path tlsDir, String controlPlaneHost)
      throws MojoExecutionException, MojoFailureException {
    for (ExampleModule example : EXAMPLES) {
      Path manifest = repoRoot().resolve(example.manifestPath());
      List<String> command =
          cliCommand(
              cliClasspath, tls, tlsDir, controlPlaneHost, "apply", "-f", manifest.toString());
      getLog().info("deploying " + example.deploymentName());
      runToCompletion(command);
    }
  }

  /**
   * Best-effort only: submission (in {@link #applyExamples}) already failed the build if a manifest
   * was rejected outright. Reaching {@code ACTIVE} afterward is inherently async (module
   * resolution, a cold worker-JVM start) -- a warning, not a build failure, on timeout, the same
   * posture {@code GreeterSmokeTestIT}'s own 60s-per-deployment {@code await()} budget takes for
   * the identical reason, just downgraded from a test assertion to a dev-convenience nudge.
   */
  private void awaitExamplesActive(
      String cliClasspath, boolean tls, Path tlsDir, String controlPlaneHost, Duration timeout) {
    for (ExampleModule example : EXAMPLES) {
      try {
        awaitTrue(
            () ->
                isDeploymentActive(
                    cliClasspath, tls, tlsDir, controlPlaneHost, example.deploymentName()),
            timeout,
            example.deploymentName() + " should reach ACTIVE");
      } catch (MojoExecutionException e) {
        getLog()
            .warn(
                e.getMessage()
                    + " -- it may still be converging; check `gimle get deployments "
                    + example.deploymentName()
                    + "` and the agent/worker logs");
      }
    }
  }

  private boolean isDeploymentActive(
      String cliClasspath,
      boolean tls,
      Path tlsDir,
      String controlPlaneHost,
      String deploymentName) {
    List<String> command =
        cliCommand(
            cliClasspath,
            tls,
            tlsDir,
            controlPlaneHost,
            "get",
            "deployments",
            deploymentName,
            "-o",
            "json");
    try {
      return runCapturing(command).contains("\"lifecycleState\":\"ACTIVE\"");
    } catch (MojoExecutionException | MojoFailureException e) {
      return false;
    }
  }

  /** Runs {@code command} to completion, output inherited into this Maven process's own console. */
  private void runToCompletion(List<String> command)
      throws MojoExecutionException, MojoFailureException {
    Process process;
    try {
      process = new ProcessBuilder(command).inheritIO().start();
    } catch (IOException e) {
      throw new MojoExecutionException("failed to start " + command.get(0), e);
    }
    waitForSuccess(process, command);
  }

  /** Runs {@code command} to completion, capturing and returning its combined stdout/stderr. */
  private String runCapturing(List<String> command)
      throws MojoExecutionException, MojoFailureException {
    Process process;
    try {
      process = new ProcessBuilder(command).redirectErrorStream(true).start();
    } catch (IOException e) {
      throw new MojoExecutionException("failed to start " + command.get(0), e);
    }
    String output;
    try {
      output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new MojoExecutionException("failed to read output from " + command.get(0), e);
    }
    waitForSuccess(process, command, output);
    return output;
  }

  private void waitForSuccess(Process process, List<String> command)
      throws MojoExecutionException, MojoFailureException {
    waitForSuccess(process, command, null);
  }

  private void waitForSuccess(Process process, List<String> command, String capturedOutput)
      throws MojoExecutionException, MojoFailureException {
    int exitCode;
    try {
      exitCode = process.waitFor();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      process.destroy();
      throw new MojoExecutionException("interrupted while waiting for " + command.get(0));
    }
    if (exitCode != 0) {
      String suffix = capturedOutput == null ? "" : ":\n" + capturedOutput;
      throw new MojoFailureException(
          String.join(" ", command) + " exited with code " + exitCode + suffix);
    }
  }

  // ---- readiness polling ----

  private void awaitTrue(BooleanSupplier condition, Duration timeout, String description)
      throws MojoExecutionException {
    long deadline = System.nanoTime() + timeout.toNanos();
    while (!condition.getAsBoolean()) {
      if (System.nanoTime() > deadline) {
        throw new MojoExecutionException("timed out waiting for: " + description);
      }
      try {
        Thread.sleep(500);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new MojoExecutionException("interrupted while waiting for: " + description);
      }
    }
  }

  private static boolean isPortOpen(int port) {
    try (Socket socket = new Socket()) {
      socket.connect(new InetSocketAddress("127.0.0.1", port), 500);
      return true;
    } catch (IOException e) {
      return false;
    }
  }

  // ---- shutdown ----

  /**
   * Reverse of spawn order (store, control plane, agent) -- nothing still running should be left
   * racing a peer it depends on that's already gone, mirroring {@code
   * GreeterSmokeTestIT.tearDown}'s own reasoning. Killing an already-dead process is a harmless
   * no-op, so this is safe to call both from the shutdown hook and from this goal's own {@code
   * finally} block without double-kill issues.
   */
  private void shutdownAll(List<Process> processes) {
    for (int i = processes.size() - 1; i >= 0; i--) {
      Process process = processes.get(i);
      process.descendants().forEach(ProcessHandle::destroy);
      process.destroyForcibly();
    }
  }

  private void printSummary(boolean tls, String controlPlaneHost, Path base) {
    String scheme = tls ? "https" : "http";
    String controlPlaneUrl = scheme + "://" + controlPlaneHost + ":" + CONTROLPLANE_PORT;
    getLog().info("");
    getLog().info("gimle cluster is up:");
    getLog().info("  control plane : " + controlPlaneUrl);
    getLog().info("  web console   : " + controlPlaneUrl + "/console");
    getLog().info("  process logs  : " + base.resolve("logs"));
    if (tls) {
      getLog().info("  operator cert : " + base.resolve("tls").resolve("operator.crt"));
      getLog()
          .info(
              "  bootstrap console admin account: see the 'generating cluster CA' output above"
                  + " for the one-time password");
    }
    getLog().info("");
  }
}
