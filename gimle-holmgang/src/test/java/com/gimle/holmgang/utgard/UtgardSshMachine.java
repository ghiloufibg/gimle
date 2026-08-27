package com.gimle.holmgang.utgard;

import com.gimle.holmgang.HolmgangException;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.utility.MountableFile;

/**
 * A single real, sshd-equipped Docker container standing in for one remote machine reachable only
 * over SSH from the test JVM -- the harness {@link UtgardSshDeployIT} uses to prove {@code hilmir
 * up/down/status --remote} genuinely dispatches over a real {@code ssh}/{@code scp} round trip,
 * unlike every other Utgard scenario's own {@link UtgardMachines#hilmir}, which uses {@code docker
 * exec} (an in-container shortcut {@code --remote} deliberately never takes). Built from the exact
 * {@code compose/ssh-remote/Dockerfile} and {@code sshd-entrypoint.sh} the manual docker-compose
 * verification scenario already uses (see that directory's own README section) -- one real sshd,
 * one real authorized key, no {@code docker exec} anywhere in this class.
 *
 * <p>Deliberately single-machine, for the identical reason {@code topology-ssh-remote.yaml}'s own
 * header comment gives: a container has two different addresses depending on audience (the Docker
 * network alias a peer container would use vs. the mapped host port the test JVM's own {@code ssh}
 * client, running outside every container, must use instead), a split a real fleet's machines never
 * have to reconcile. Proving the SSH transport itself needs only one machine; a genuine
 * multi-machine SSH round trip through Docker would need {@code SshSettings}/{@code Machine} to
 * carry a connection-address override distinct from the topology's own inter-process {@code host},
 * which does not exist today.
 *
 * <p>Unlike {@link UtgardMachines}, which copies the test JVM's own reactor classpath (including
 * bare {@code target/classes} directories) into every container and invokes {@code
 * com.gimle.hilmir.HilmirMain} directly, this class stages a real {@code bin/hilmir} launcher --
 * the exact file {@code gimle-dist} ships -- plus its {@code lib/*.jar} dependency closure: {@code
 * RemoteDispatch}/{@code SshProcessExec} always exec {@code <installDir>/bin/hilmir}, a shell
 * script that globs only {@code .jar} files, so a plain classpath-directory copy (the {@code
 * UtgardMachines} trick) would leave that script silently finding nothing.
 *
 * <p>Public (rather than the package-private visibility this class started with) so {@code
 * gimle-ragnarok}'s own SSH-backed inventory target can reuse this exact container fixture from a
 * sibling test package instead of building a parallel one -- a pure visibility widening, no
 * behavior change.
 */
public final class UtgardSshMachine implements AutoCloseable {

  static final int SSH_PORT = 22;
  static final int CONTROL_PLANE_PORT = UtgardMachines.CONTROL_PLANE_PORT;
  static final int FAFNIR_PORT = UtgardMachines.FAFNIR_PORT;
  static final int STORE_CLIENT_PORT = UtgardMachines.STORE_CLIENT_PORT;
  static final String SSH_USER = "operator";
  static final String INSTALL_DIR = "/opt/gimle";

  private static final String GIMLE_VERSION = "0.1.0-alpha.2";
  private static final List<String> EXTERNAL_RUNTIME_JAR_PREFIXES =
      List.of("slf4j-api-", "logback-classic-", "logback-core-", "snakeyaml-");
  private static final Path DOCKERFILE = Path.of("compose", "ssh-remote", "Dockerfile");
  private static final Path ENTRYPOINT_SCRIPT =
      Path.of("compose", "ssh-remote", "sshd-entrypoint.sh");
  private static final Duration STARTUP_TIMEOUT = Duration.ofMinutes(5);

  private final GenericContainer<?> container;

  private UtgardSshMachine(final GenericContainer<?> container) {
    this.container = container;
  }

  /**
   * Builds the {@code ssh-remote} image, boots one container authorizing {@code publicKeyFile} for
   * the {@code operator} user, and stages a real {@code bin/hilmir} launcher plus its runtime jars
   * -- the exact archive layout {@code --remote}'s own {@code ResolvedSshTarget#remoteHilmirBinary}
   * expects. Any failure anywhere in this process -- no reachable daemon, a blocked base-image
   * pull, a container that never opens its own SSH port -- is wrapped in {@link
   * UtgardDockerUnavailableException}, mirroring {@link UtgardMachines#start}'s own contract.
   */
  static UtgardSshMachine start(final Path publicKeyFile) {
    try {
      return doStart(publicKeyFile);
    } catch (final RuntimeException | IOException e) {
      throw new UtgardDockerUnavailableException(
          "could not start Utgard's SSH machine -- no reachable Docker daemon, or the "
              + "ssh-remote image could not be built (see cause): "
              + e.getMessage(),
          e);
    }
  }

  private static UtgardSshMachine doStart(final Path publicKeyFile) throws IOException {
    final byte[] publicKeyBytes = Files.readAllBytes(publicKeyFile);
    final GenericContainer<?> container =
        new GenericContainer<>(new ImageFromDockerfile().withDockerfile(DOCKERFILE))
            .withExposedPorts(SSH_PORT, CONTROL_PLANE_PORT, FAFNIR_PORT, STORE_CLIENT_PORT)
            .withCopyToContainer(Transferable.of(publicKeyBytes), "/run/secrets/operator-pubkey")
            .withCopyFileToContainer(
                MountableFile.forHostPath(ENTRYPOINT_SCRIPT), "/holmgang/sshd-entrypoint.sh")
            .withCommand("/bin/sh", "/holmgang/sshd-entrypoint.sh")
            .waitingFor(Wait.forListeningPorts(SSH_PORT))
            .withStartupTimeout(STARTUP_TIMEOUT);
    container.start();
    installHilmirLauncher(container);
    return new UtgardSshMachine(container);
  }

  int mappedSshPort() {
    return container.getMappedPort(SSH_PORT);
  }

  int mappedControlPlanePort() {
    return container.getMappedPort(CONTROL_PLANE_PORT);
  }

  int mappedFafnirPort() {
    return container.getMappedPort(FAFNIR_PORT);
  }

  int mappedStoreClientPort() {
    return container.getMappedPort(STORE_CLIENT_PORT);
  }

  /** Writes a host file into the machine's install root, e.g. a deployment's own module jar. */
  void copyHostFileToContainer(final Path hostFile, final String containerPath) {
    container.copyFileToContainer(MountableFile.forHostPath(hostFile), containerPath);
  }

  @Override
  public void close() {
    container.stop();
  }

  private static void installHilmirLauncher(final GenericContainer<?> container) {
    container.copyFileToContainer(
        MountableFile.forHostPath(hilmirLauncherScript(), 0755), INSTALL_DIR + "/bin/hilmir");
    for (final Path jar : runtimeJars()) {
      container.copyFileToContainer(
          MountableFile.forHostPath(jar), INSTALL_DIR + "/lib/" + jar.getFileName());
    }
  }

  private static Path repoRoot() {
    return Path.of("").toAbsolutePath().getParent();
  }

  /** The real launcher {@code gimle-dist} ships -- see that module's own {@code hilmir.xml}. */
  private static Path hilmirLauncherScript() {
    final Path script = repoRoot().resolve("gimle-dist/src/main/dist/bin/hilmir");
    if (!Files.isRegularFile(script)) {
      throw new HolmgangException("expected the real hilmir launcher script at " + script);
    }
    return script;
  }

  /**
   * The jars {@code bin/hilmir}'s own {@code lib/*.jar} glob needs -- the same dependency set
   * {@code gimle-dist}'s {@code hilmir.xml} assembly enumerates: the two reactor modules (resolved
   * by relative path, since a reactor build's own compile classpath usually carries their bare
   * {@code target/classes} directory instead of a packaged jar -- mirroring {@code
   * UtgardDistributedBootIT#exampleJar}'s identical trick) plus every third-party runtime
   * dependency, which -- never being a reactor module of this build -- is already a real jar
   * resolved from the local repository on the test JVM's own classpath.
   */
  private static List<Path> runtimeJars() {
    final List<Path> jars = new ArrayList<>();
    jars.add(moduleJar("gimle-hilmir", "gimle-hilmir"));
    jars.add(moduleJar("gimle-core", "gimle-core"));
    jars.addAll(externalRuntimeJars());
    return jars;
  }

  private static Path moduleJar(final String moduleDir, final String artifactId) {
    final Path jar =
        repoRoot()
            .resolve(moduleDir)
            .resolve("target")
            .resolve(artifactId + "-" + GIMLE_VERSION + ".jar");
    if (!Files.isRegularFile(jar)) {
      throw new HolmgangException(
          "expected a built jar at " + jar + " -- run `mvn install` before -Pvalidation");
    }
    return jar;
  }

  private static List<Path> externalRuntimeJars() {
    final List<Path> jars = new ArrayList<>();
    final String classpath = System.getProperty("java.class.path");
    for (final String part : classpath.split(File.pathSeparator)) {
      if (part.isBlank() || !part.endsWith(".jar")) {
        continue;
      }
      final Path path = Path.of(part).toAbsolutePath();
      final String fileName = path.getFileName().toString();
      for (final String prefix : EXTERNAL_RUNTIME_JAR_PREFIXES) {
        if (fileName.startsWith(prefix) && Files.exists(path)) {
          jars.add(path);
          break;
        }
      }
    }
    if (jars.size() < EXTERNAL_RUNTIME_JAR_PREFIXES.size()) {
      throw new HolmgangException(
          "expected to resolve one jar per "
              + EXTERNAL_RUNTIME_JAR_PREFIXES
              + " on the test JVM's own classpath, found: "
              + jars);
    }
    return jars;
  }
}
