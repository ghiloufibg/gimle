package com.gimle.holmgang.utgard;

import com.gimle.holmgang.HolmgangException;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.model.ContainerNetwork;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.Container;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * A fleet of real Docker containers standing in for {@code gimle-dist}'s (not-yet-built) platform
 * tarball: one container per declared machine, all on one shared {@link Network}, each aliased to
 * its own machine name -- so every machine is a real, independently addressable host on a real
 * Docker network, not a loopback port trick the way {@code GimleCluster}'s single-machine topology
 * is. Every container gets the test JVM's own reactor classpath copied in (the same "the test JVM's
 * classpath becomes every spawned process's classpath" trick {@code GimleCluster} already relies
 * on), so {@code hilmir} and every {@code com.gimle.*Main} class run inside it exactly as they
 * would from a real install.
 *
 * <p>Lifecycle is deliberately simple rather than pooled like {@code
 * com.gimle.holmgang.steps.ClusterPool}: each of Utgard's four {@code *IT} classes owns one fleet
 * for its own class's lifetime (manual {@code @BeforeAll}/{@code @AfterAll}, not the
 * {@code @Testcontainers}/{@code @Container} annotation pair) because every one of the four
 * scenarios is itself destructive in some way (kills a machine, partitions the network, or mutates
 * cluster membership through repeated {@code hilmir up} calls) -- there is no non-destructive
 * scenario here to pool across the way {@code ClusterPool} pools Gherkin scenarios, and four fleets
 * is too few to make that machinery worth its own complexity. Manual lifecycle also gives {@link
 * #start} a single place to convert *any* failure (no daemon, or -- the specific wall this sandbox
 * hits -- a blocked image pull) into {@link UtgardDockerUnavailableException}, which every {@code
 * *IT} class's own {@code @BeforeAll} catches and turns into a clean JUnit assumption-based skip;
 * the {@code @Testcontainers} extension's own automatic container lifecycle has no equivalent hook
 * to convert a startup failure into a skip rather than a hard failure.
 */
public final class UtgardMachines implements AutoCloseable {

  /** Default ports every {@code gimle-hilmir} topology assigns when a replica omits its own. */
  public static final int STORE_RAFT_PORT = 9080;

  public static final int STORE_CLIENT_PORT = 9091;
  public static final int CONTROL_PLANE_PORT = 8080;
  public static final int FAFNIR_PORT = 9092;
  public static final int MUNINN_PORT = 9093;
  public static final int ANDVARI_PORT = 9094;
  public static final int AGENT_GOSSIP_PORT = 9090;

  private static final DockerImageName IMAGE = DockerImageName.parse("eclipse-temurin:25-jdk");
  private static final String LIB_DIR = "/opt/gimle/lib";
  private static final String READY_MARKER = "utgard-machine-ready";
  private static final Duration STARTUP_TIMEOUT = Duration.ofMinutes(5);

  private final Network network;
  private final Map<String, GenericContainer<?>> containers;
  private final String containerClasspath;

  private UtgardMachines(
      final Network network,
      final Map<String, GenericContainer<?>> containers,
      final String containerClasspath) {
    this.network = network;
    this.containers = containers;
    this.containerClasspath = containerClasspath;
  }

  /**
   * Starts one container per name in {@code machineNames}, each aliased to itself on a shared
   * network. Any failure anywhere in this process -- no reachable daemon, a blocked base-image
   * pull, a container that never reaches its own ready marker -- is wrapped in {@link
   * UtgardDockerUnavailableException} rather than left as whatever raw Testcontainers/Docker
   * exception happened to surface, so every caller has exactly one type to catch.
   */
  public static UtgardMachines start(final List<String> machineNames) {
    if (machineNames == null || machineNames.isEmpty()) {
      throw new HolmgangException("an Utgard fleet needs at least one machine name");
    }
    try {
      return doStart(machineNames);
    } catch (final RuntimeException e) {
      throw new UtgardDockerUnavailableException(
          "could not start Utgard's container fleet -- no reachable Docker daemon, or the "
              + IMAGE.asCanonicalNameString()
              + " base image could not be pulled (see cause): "
              + e.getMessage(),
          e);
    }
  }

  private static UtgardMachines doStart(final List<String> machineNames) {
    final List<ClasspathEntry> entries = resolveClasspathEntries();
    final String containerClasspath = joinContainerPaths(entries);

    final Network network = Network.newNetwork();
    final Map<String, GenericContainer<?>> containers = new LinkedHashMap<>();
    for (final String name : machineNames) {
      final GenericContainer<?> container =
          new GenericContainer<>(IMAGE)
              .withNetwork(network)
              .withNetworkAliases(name)
              .withExposedPorts(
                  CONTROL_PLANE_PORT,
                  STORE_RAFT_PORT,
                  STORE_CLIENT_PORT,
                  FAFNIR_PORT,
                  MUNINN_PORT,
                  ANDVARI_PORT,
                  AGENT_GOSSIP_PORT)
              // A plain "sleep infinity" gives Testcontainers' default port-liveness wait
              // strategy nothing to observe (nothing listens on any exposed port until a real
              // gimle process is later spawned inside it), so the container instead announces
              // itself on stdout and the wait strategy watches for exactly that line.
              .withCommand("sh", "-c", "echo " + READY_MARKER + " && sleep infinity")
              .waitingFor(Wait.forLogMessage(".*" + READY_MARKER + ".*\\n", 1))
              .withStartupTimeout(STARTUP_TIMEOUT);
      for (final ClasspathEntry entry : entries) {
        container.withCopyFileToContainer(entry.mountable(), entry.containerPath());
      }
      container.start();
      containers.put(name, container);
    }
    return new UtgardMachines(network, containers, containerClasspath);
  }

  public GenericContainer<?> container(final String machine) {
    final GenericContainer<?> container = containers.get(machine);
    if (container == null) {
      throw new HolmgangException("no Utgard machine named '" + machine + "' in this fleet");
    }
    return container;
  }

  public List<String> machines() {
    return List.copyOf(containers.keySet());
  }

  public String containerClasspath() {
    return containerClasspath;
  }

  public int mappedPort(final String machine, final int containerPort) {
    return container(machine).getMappedPort(containerPort);
  }

  public void writeFile(final String machine, final String content, final String containerPath) {
    container(machine)
        .copyFileToContainer(
            Transferable.of(content.getBytes(StandardCharsets.UTF_8)), containerPath);
  }

  public void writeFileToEveryMachine(final String content, final String containerPath) {
    for (final String machine : containers.keySet()) {
      writeFile(machine, content, containerPath);
    }
  }

  public void copyHostFileToContainer(
      final String machine, final Path hostFile, final String containerPath) {
    container(machine).copyFileToContainer(MountableFile.forHostPath(hostFile), containerPath);
  }

  /** Runs {@code hilmir <verbAndArgs>} inside {@code machine}'s own container, blocking on it. */
  public UtgardExecResult hilmir(final String machine, final String... verbAndArgs) {
    final List<String> command = new ArrayList<>();
    command.add("java");
    command.add("-cp");
    command.add(containerClasspath);
    command.add("com.gimle.hilmir.HilmirMain");
    command.addAll(List.of(verbAndArgs));
    return exec(machine, command);
  }

  public UtgardExecResult exec(final String machine, final List<String> command) {
    final GenericContainer<?> container = container(machine);
    try {
      final Container.ExecResult result = container.execInContainer(command.toArray(new String[0]));
      return new UtgardExecResult(
          command, result.getExitCode(), result.getStdout(), result.getStderr());
    } catch (final IOException e) {
      throw new HolmgangException("failed executing " + command + " in container " + machine, e);
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new HolmgangException(
          "interrupted executing " + command + " in container " + machine, e);
    }
  }

  /**
   * Hard-kills the machine's whole container -- the harshest failure a real machine can suffer
   * (power loss), deliberately not a graceful {@code docker stop}: every process inside, including
   * the platform's own agent, disappears with no chance to deregister itself or drain.
   */
  public void killMachine(final String machine) {
    final GenericContainer<?> container = container(machine);
    dockerClient().killContainerCmd(container.getContainerId()).exec();
  }

  /**
   * Restarts a previously killed machine's container. Its writable filesystem -- any topology file,
   * TLS material, or {@code hilmir-run.json} ledger a prior {@code hilmir} invocation wrote --
   * survives, since {@code docker start} reuses the same container rather than creating a new one;
   * nothing inside is actually running again until a fresh {@code hilmir up} is issued against it,
   * which is exactly the rejoin path {@code UtgardMachineLossIT} exercises.
   */
  public void restartMachine(final String machine) {
    final GenericContainer<?> container = container(machine);
    dockerClient().startContainerCmd(container.getContainerId()).exec();
  }

  /**
   * Disconnects {@code machine} from this fleet's shared network -- a genuine machine partition.
   */
  public void disconnectFromNetwork(final String machine) {
    final GenericContainer<?> container = container(machine);
    dockerClient()
        .disconnectFromNetworkCmd()
        .withContainerId(container.getContainerId())
        .withNetworkId(network.getId())
        .exec();
  }

  /** The inverse of {@link #disconnectFromNetwork}, restoring {@code machine}'s own alias. */
  public void reconnectToNetwork(final String machine) {
    final GenericContainer<?> container = container(machine);
    dockerClient()
        .connectToNetworkCmd()
        .withContainerId(container.getContainerId())
        .withNetworkId(network.getId())
        .withContainerNetwork(new ContainerNetwork().withAliases(List.of(machine)))
        .exec();
  }

  /**
   * A real {@code docker cp}-equivalent for moving a whole directory between two containers:
   * Testcontainers has no direct container-to-container copy, so this tars the directory inside the
   * source container, pulls the tarball down to {@code stagingDir} on the test host, then pushes
   * the extracted directory into the destination container -- the test host plays the same
   * intermediary role a real operator's own workstation would, distributing generated PKI material
   * by hand.
   */
  public void copyDirectoryBetweenContainers(
      final String fromMachine,
      final String toMachine,
      final String containerDirPath,
      final Path stagingDir) {
    final String dirName = baseName(containerDirPath);
    copyDirectoryFromContainer(fromMachine, containerDirPath, stagingDir);
    copyDirectoryToContainer(toMachine, stagingDir.resolve(dirName), containerDirPath);
  }

  /**
   * Pulls {@code containerDirPath} out of {@code machine} into {@code localDestDir} on the host.
   */
  public void copyDirectoryFromContainer(
      final String machine, final String containerDirPath, final Path localDestDir) {
    final String tarName = "utgard-pull-" + System.nanoTime() + ".tar";
    final String parent = parentPath(containerDirPath);
    final String dirName = baseName(containerDirPath);
    UtgardExec.requireSuccess(
        exec(machine, List.of("tar", "cf", "/tmp/" + tarName, "-C", parent, dirName)),
        "tar " + containerDirPath + " on " + machine);
    createDirectories(localDestDir);
    final Path localTar = localDestDir.resolve(tarName);
    container(machine).copyFileFromContainer("/tmp/" + tarName, localTar.toString());
    extractTar(localTar, localDestDir);
  }

  /**
   * Pushes a host directory into {@code machine} so it lands at exactly {@code containerDirPath}.
   */
  public void copyDirectoryToContainer(
      final String machine, final Path localSourceDir, final String containerDirPath) {
    container(machine)
        .copyFileToContainer(MountableFile.forHostPath(localSourceDir), containerDirPath);
  }

  private DockerClient dockerClient() {
    return DockerClientFactory.instance().client();
  }

  @Override
  public void close() {
    for (final GenericContainer<?> container : containers.values()) {
      try {
        container.stop();
      } catch (final RuntimeException e) {
        // Best-effort teardown: a container a test already killed is not a close failure.
      }
    }
    network.close();
  }

  private static void extractTar(final Path tarFile, final Path destDir) {
    final ProcessBuilder processBuilder =
        new ProcessBuilder("tar", "xf", tarFile.toString(), "-C", destDir.toString());
    processBuilder.redirectErrorStream(true);
    try {
      final Process process = processBuilder.start();
      final String output =
          new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
      if (!process.waitFor(30, TimeUnit.SECONDS) || process.exitValue() != 0) {
        throw new HolmgangException(
            "failed extracting " + tarFile + " into " + destDir + ": " + output);
      }
    } catch (final IOException e) {
      throw new HolmgangException("failed extracting " + tarFile, e);
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new HolmgangException("interrupted extracting " + tarFile, e);
    }
  }

  private static void createDirectories(final Path dir) {
    try {
      Files.createDirectories(dir);
    } catch (final IOException e) {
      throw new HolmgangException("failed creating directory " + dir, e);
    }
  }

  private static String parentPath(final String path) {
    final int slash = path.lastIndexOf('/');
    return slash <= 0 ? "/" : path.substring(0, slash);
  }

  private static String baseName(final String path) {
    final int slash = path.lastIndexOf('/');
    return slash < 0 ? path : path.substring(slash + 1);
  }

  private record ClasspathEntry(Path hostPath, String containerPath) {
    MountableFile mountable() {
      return MountableFile.forHostPath(hostPath);
    }
  }

  /**
   * Every real, existing entry on the test JVM's own {@code java.class.path}, each given its own
   * numbered destination under {@link #LIB_DIR} inside a container -- numbered because two reactor
   * modules can legitimately share a bare {@code classes}/{@code target} basename, which an
   * unnumbered destination would silently collide on.
   */
  private static List<ClasspathEntry> resolveClasspathEntries() {
    final String classpath = System.getProperty("java.class.path");
    final List<ClasspathEntry> entries = new ArrayList<>();
    int index = 0;
    for (final String part : classpath.split(File.pathSeparator)) {
      if (part.isBlank()) {
        continue;
      }
      final Path hostPath = Path.of(part).toAbsolutePath();
      if (!Files.exists(hostPath)) {
        continue;
      }
      entries.add(
          new ClasspathEntry(hostPath, LIB_DIR + "/" + index + "-" + hostPath.getFileName()));
      index++;
    }
    if (entries.isEmpty()) {
      throw new HolmgangException(
          "the test JVM's own classpath resolved to zero real paths to copy");
    }
    return entries;
  }

  private static String joinContainerPaths(final List<ClasspathEntry> entries) {
    final List<String> paths = new ArrayList<>();
    for (final ClasspathEntry entry : entries) {
      paths.add(entry.containerPath());
    }
    return String.join(":", paths);
  }
}
