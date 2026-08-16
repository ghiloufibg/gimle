package com.gimle.mavenplugin;

import java.time.Duration;
import java.util.List;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.repository.RemoteRepository;

/**
 * {@code mvn gimle:saga} -- ensures a Saga test-report server is up on {@code gimle.saga.port} and
 * prints its console URL. A healthy server already listening there is reused as-is (it is a shared,
 * long-lived local service, not per-build state); otherwise a real {@code SagaMain} process is
 * spawned detached -- output to {@code ~/.gimle/saga/saga.log}, pid recorded beside it for {@code
 * gimle:saga-stop} -- on {@code gimle-saga}'s own resolved runtime classpath, the same
 * already-installed-artifact resolution {@link PublishMojo} uses for the CLI. Runs once per
 * invocation, at the execution root (see {@link AbstractGimleRootMojo}).
 */
@Mojo(name = "saga", threadSafe = true)
public final class SagaMojo extends AbstractGimleRootMojo {

  static final Duration STARTUP_TIMEOUT = Duration.ofSeconds(30);

  // 9096: clear of the sibling gimle:* goals' own defaults (store raft 9080 / client 9091,
  // fafnir 9092, muninn 9093, andvari 9094).
  @Parameter(property = "gimle.saga.port", defaultValue = "9096")
  private String port;

  /** Overridable so an out-of-tree project can pin a server build other than this plugin's own. */
  @Parameter(property = "gimle.saga.serverVersion", defaultValue = "${plugin.version}")
  private String serverVersion;

  @Parameter(
      defaultValue = "${project.remoteProjectRepositories}",
      readonly = true,
      required = true)
  private List<RemoteRepository> remoteRepositories;

  @Parameter(defaultValue = "${repositorySystemSession}", readonly = true, required = true)
  private RepositorySystemSession repositorySystemSession;

  @Component private RepositorySystem repositorySystem;

  @Override
  protected void executeAtRoot() throws MojoExecutionException {
    SagaClient client = new SagaClient("http://127.0.0.1:" + port);
    SagaServer.Ensured ensured =
        SagaServer.ensureRunning(client, this::spawnServer, STARTUP_TIMEOUT, getLog());
    String verb = ensured == SagaServer.Ensured.REUSED ? "reusing running" : "started";
    getLog().info(verb + " Saga server -- console: " + client.endpoint() + "/console");
  }

  private Process spawnServer() throws MojoExecutionException {
    String classpath =
        GimleProcesses.resolveRuntimeClasspath(
            "gimle-saga",
            serverVersion,
            remoteRepositories,
            repositorySystemSession,
            repositorySystem);
    return SagaServer.spawnDetached(
        SagaServer.spawnCommand(GimleProcesses.javaExecutable(), classpath, port), getLog());
  }
}
