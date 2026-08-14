package com.gimle.mavenplugin;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
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
 * {@code mvn gimle:bootstrap [-Dgimle.bootstrap.protocol=plaintext|tls] [-Dgimle.bootstrap.clean]}
 * -- collapses the multi-terminal local-dev walkthrough ({@code gimle-console/LOCAL_DEV.md}: {@code
 * tls-init} if TLS, {@code store}, {@code controlplane}, {@code agent}, then a {@code cert token
 * create} + {@code apply} per manifest if TLS) into one foreground command: brings up a single-node
 * store + control plane + agent (with its own worker child, same as {@code gimle:agent} today),
 * deploys every {@code gimle-examples} module once the cluster is ready, prints a summary, then
 * blocks until interrupted and tears the whole cluster back down.
 *
 * <p>{@code gimle-bootstrap/} (store Raft state, secret key files, Muninn's day files, TLS
 * material) survives between runs by design, so stopping and restarting this goal resumes the same
 * cluster rather than starting from scratch. The cost of that persistence: every persisted format
 * here (the Raft log entry encoding above all) is read back with no version tag or compatibility
 * shim -- deliberately, per this project's own "no backward-compat needed pre-release" stance -- so
 * a {@code gimle-bootstrap/} directory left over from before such a format changed will make the
 * affected process (typically the store, via {@code RaftLog}'s constructor) crash before it ever
 * opens its port, which surfaces here only as this goal's own readiness-timeout error. {@code
 * -Dgimle.bootstrap.clean=true} wipes {@code gimle-bootstrap/} before spawning anything, trading
 * that persistence away for a guaranteed-fresh cluster -- the fix any time a stale local {@code
 * gimle-bootstrap/} directory is suspected, not just after a format change.
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
 * its audit story, unlike the store's own Raft/client RPC transports. A real multi-node TLS
 * deployment would need {@code gimle-pki} to mint a real per-store-node identity too -- out of
 * scope here. Also out of scope: propagating {@code gimle.tls.*} into the worker JVM this goal's
 * agent spawns, needed only for genuine cross-machine fabric TLS (same-machine fabric is a Unix
 * domain socket, never TLS'd), which a single-machine bootstrap never exercises.
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

  /**
   * The four values every CLI-driven readiness/apply helper below needs to build a {@code gimle}
   * command line against the cluster this goal just brought up -- bundled together so {@link
   * #cliCommand} and its five callers thread one value instead of four.
   */
  private record ClusterEndpoint(
      String cliClasspath, boolean tls, Path tlsDir, String controlPlaneHost) {}

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

  @Parameter(property = "gimle.bootstrap.clean", defaultValue = "false")
  private boolean clean;

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
    if (clean) {
      deleteRecursively(base);
    }
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

      String cliClasspath = resolveClasspath("gimle-cli");
      String bootstrapToken =
          tls
              ? mintBootstrapToken(
                  new ClusterEndpoint(cliClasspath, true, tlsDir, controlPlaneHost))
              : null;

      spawned.add(spawnAgent(tls, tlsDir, controlPlaneHost, bootstrapToken, logsDir));
      ClusterEndpoint endpoint = new ClusterEndpoint(cliClasspath, tls, tlsDir, controlPlaneHost);
      awaitTrue(
          () -> hasRegisteredNodes(endpoint),
          readyTimeout,
          "the agent should register a node with the control plane");

      if (deployExamples) {
        applyExamples(endpoint);
        awaitExamplesActive(endpoint, readyTimeout);
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

  /**
   * Recursively deletes {@code dir} (a no-op if it doesn't exist) -- backs {@code
   * gimle.bootstrap.clean}, see the class javadoc for why a stale {@code gimle-bootstrap/} needs
   * this rather than a per-file compatibility fix.
   */
  private void deleteRecursively(Path dir) throws MojoExecutionException {
    if (!Files.exists(dir)) {
      return;
    }
    getLog().info("cleaning " + dir + " before bootstrapping");
    try (var paths = Files.walk(dir)) {
      for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
        Files.delete(path);
      }
    } catch (IOException e) {
      throw new MojoExecutionException("failed to clean " + dir, e);
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
    return spawnGimleProcess(
        tls,
        tlsDir,
        // See the class javadoc: reuses the controlplane leaf certificate, there is no dedicated
        // store identity yet.
        "controlplane",
        // Optional -- see AgentMain's own javadoc on gimle.agent.muninnEndpoint for why this is a
        // system property rather than a new CLI flag.
        List.of("-Dgimle.store.muninnEndpoint=127.0.0.1:" + MUNINN_PORT),
        "gimle-mimir",
        "com.gimle.mimir.StoreMain",
        List.of(
            base.resolve("store-state").toString(),
            String.valueOf(STORE_RAFT_PORT),
            String.valueOf(STORE_CLIENT_PORT)),
        "starting store on client port " + STORE_CLIENT_PORT,
        logsDir.resolve("store.log"));
  }

  private Process spawnFafnir(Path base, boolean tls, Path tlsDir, Path logsDir)
      throws MojoExecutionException {
    return spawnGimleProcess(
        tls,
        tlsDir,
        // Unlike the store above, Fafnir gets its own distinct leaf identity from
        // cluster-bootstrap time -- see the class javadoc for why sharing the control plane's
        // borrowed identity, the store's own stand-in, isn't an option here.
        "fafnir",
        List.of("-Dgimle.fafnir.muninnEndpoint=127.0.0.1:" + MUNINN_PORT),
        "gimle-fafnir",
        "com.gimle.fafnir.FafnirMain",
        List.of(
            String.valueOf(FAFNIR_PORT),
            base.resolve("fafnir-secret.key").toString(),
            "--store-endpoints",
            "127.0.0.1:" + STORE_CLIENT_PORT),
        "starting fafnir on port " + FAFNIR_PORT,
        logsDir.resolve("fafnir.log"));
  }

  private Process spawnMuninn(Path base, boolean tls, Path tlsDir, Path logsDir)
      throws MojoExecutionException {
    return spawnGimleProcess(
        tls,
        tlsDir,
        // Its own dedicated leaf identity, the same reasoning as Fafnir's above: Muninn's own
        // independent Authorizer check on proxied reads needs to be attributable to its own
        // certificate Subject, not a borrowed one.
        "muninn",
        List.of(),
        "gimle-muninn",
        "com.gimle.muninn.MuninnMain",
        List.of(
            String.valueOf(MUNINN_PORT),
            "--store-endpoints",
            "127.0.0.1:" + STORE_CLIENT_PORT,
            "--data-root",
            base.resolve("muninn-data").toString()),
        "starting muninn on port " + MUNINN_PORT,
        logsDir.resolve("muninn.log"));
  }

  private Process spawnControlPlane(Path base, boolean tls, Path tlsDir, Path logsDir)
      throws MojoExecutionException {
    List<String> extraJvmArgs = new ArrayList<>();
    if (tls) {
      // Distinct from gimle.tls.keyFile (this node's own leaf key): the cluster CA's own private
      // key, needed so this control plane can sign incoming CSRs and mint bootstrap tokens at
      // /bootstrap/csr and /bootstrap/tokens (see CaKeyMaterial's own javadoc). A single-node
      // bootstrap has exactly one control-plane replica, so it's always the one holding
      // cluster-signing authority here.
      extraJvmArgs.add("-Dgimle.pki.caKeyFile=" + tlsDir.resolve("ca.key"));
      // The one-time bootstrap admin account runTlsInit's PkiBootstrapMain call just minted and
      // printed to this goal's own console output -- without this, BootstrapAccountFile never
      // finds the file, no Account is ever seeded while the store has zero accounts, and that
      // printed password can never actually log in.
      extraJvmArgs.add("-Dgimle.bootstrap.accountFile=" + tlsDir.resolve("bootstrap-account.yaml"));
    }
    return spawnGimleProcess(
        tls,
        tlsDir,
        "controlplane",
        extraJvmArgs,
        "gimle-controlplane",
        "com.gimle.controlplane.ControlPlaneMain",
        List.of(
            String.valueOf(CONTROLPLANE_PORT),
            base.resolve("controlplane-secret.key").toString(),
            "--store-endpoints",
            "127.0.0.1:" + STORE_CLIENT_PORT,
            "--fafnir-endpoint",
            "127.0.0.1:" + FAFNIR_PORT,
            // Optional -- lets this replica's /logs/* proxy fall back to Muninn's own shipped
            // history for a gone node/instance instead of a bare 404/502.
            "--muninn-endpoint",
            "127.0.0.1:" + MUNINN_PORT),
        "starting control plane on port " + CONTROLPLANE_PORT,
        logsDir.resolve("controlplane.log"));
  }

  /**
   * Shared skeleton behind {@link #spawnStore}/{@link #spawnFafnir}/{@link #spawnMuninn}/{@link
   * #spawnControlPlane}: build the {@code java -cp ... mainClass args...} command line, adding TLS
   * flags for {@code certName}'s leaf certificate when {@code tls} is set, then hand it to {@link
   * #spawnLongRunning}. The four callers differ only in which cert they present, which extra JVM
   * system properties they need, which module's classpath/main class to launch, and which
   * positional args that main class takes.
   */
  private Process spawnGimleProcess(
      boolean tls,
      Path tlsDir,
      String certName,
      List<String> extraJvmArgs,
      String classpathArtifactId,
      String mainClass,
      List<String> args,
      String startingLogMessage,
      Path logFile)
      throws MojoExecutionException {
    List<String> command = new ArrayList<>();
    command.add(GimleProcesses.javaExecutable());
    if (tls) {
      addTlsFlags(
          command,
          tlsDir.resolve(certName + ".crt"),
          tlsDir.resolve(certName + ".key"),
          tlsDir.resolve("ca.crt"));
    }
    command.addAll(extraJvmArgs);
    command.add("-cp");
    command.add(resolveClasspath(classpathArtifactId));
    command.add(mainClass);
    command.addAll(args);
    getLog().info(startingLogMessage);
    return spawnLongRunning(command, logFile);
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
    // Lets this agent fetch secret values straight from Fafnir instead of relying on the control
    // plane to have already decrypted them -- see AgentMain's own javadoc on
    // gimle.agent.fafnirEndpoint for why this is a system property rather than a new positional
    // arg.
    command.add("-Dgimle.agent.fafnirEndpoint=127.0.0.1:" + FAFNIR_PORT);
    // Same reasoning, for shipping this agent's own + every supervised worker's logs to Muninn --
    // see AgentMain's own javadoc on gimle.agent.muninnEndpoint.
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

  private List<String> cliCommand(ClusterEndpoint endpoint, String... args) {
    List<String> command = new ArrayList<>();
    command.add(GimleProcesses.javaExecutable());
    if (endpoint.tls()) {
      addTlsFlags(
          command,
          endpoint.tlsDir().resolve("operator.crt"),
          endpoint.tlsDir().resolve("operator.key"),
          endpoint.tlsDir().resolve("ca.crt"));
    }
    command.add("-cp");
    command.add(endpoint.cliClasspath());
    command.add("com.gimle.cli.GimleCli");
    command.addAll(List.of(args));
    command.add("--server");
    command.add(endpoint.controlPlaneHost() + ":" + CONTROLPLANE_PORT);
    return command;
  }

  private String mintBootstrapToken(ClusterEndpoint endpoint)
      throws MojoExecutionException, MojoFailureException {
    List<String> command = cliCommand(endpoint, "cert", "token", "create");
    getLog().info("minting a node bootstrap token");
    String output = runCapturing(command);
    Matcher matcher = Pattern.compile("bootstrap token: (\\S+)").matcher(output);
    if (!matcher.find()) {
      throw new MojoExecutionException(
          "could not find a bootstrap token in CLI output:\n" + output);
    }
    return matcher.group(1);
  }

  private boolean hasRegisteredNodes(ClusterEndpoint endpoint) {
    List<String> command = cliCommand(endpoint, "get", "nodes", "-o", "json");
    try {
      return !runCapturing(command).strip().equals("[]");
    } catch (MojoExecutionException | MojoFailureException e) {
      return false;
    }
  }

  private void applyExamples(ClusterEndpoint endpoint)
      throws MojoExecutionException, MojoFailureException {
    for (ExampleModule example : EXAMPLES) {
      Path manifest = repoRoot().resolve(example.manifestPath());
      List<String> command = cliCommand(endpoint, "apply", "-f", manifest.toString());
      getLog().info("deploying " + example.deploymentName());
      runToCompletion(command);
    }
  }

  /**
   * Best-effort only: submission (in {@link #applyExamples}) already failed the build if a manifest
   * was rejected outright. Reaching {@code ACTIVE} afterward is inherently async (module
   * resolution, a cold worker-JVM start) -- a warning, not a build failure, on timeout, the same
   * posture gimle-smoke-tests' own {@code GreeterSmokeClusterSupport}'s 60s-per-deployment {@code
   * await()} budget takes for the identical reason, just downgraded from a test assertion to a
   * dev-convenience nudge.
   */
  private void awaitExamplesActive(ClusterEndpoint endpoint, Duration timeout) {
    for (ExampleModule example : EXAMPLES) {
      try {
        awaitTrue(
            () -> isDeploymentActive(endpoint, example.deploymentName()),
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

  private boolean isDeploymentActive(ClusterEndpoint endpoint, String deploymentName) {
    List<String> command = cliCommand(endpoint, "get", "deployments", deploymentName, "-o", "json");
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
   * racing a peer it depends on that's already gone, mirroring gimle-smoke-tests' own {@code
   * GreeterSmokeClusterSupport.tearDown}'s own reasoning. Killing an already-dead process is a
   * harmless no-op, so this is safe to call both from the shutdown hook and from this goal's own
   * {@code finally} block without double-kill issues.
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
