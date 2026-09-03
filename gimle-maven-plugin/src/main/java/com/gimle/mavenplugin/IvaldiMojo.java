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
 * {@code mvn gimle:ivaldi} -- ensures an Ivaldi cluster-designer server is up on {@code
 * gimle.ivaldi.port} and prints its console URL. A healthy server already listening there is reused
 * as-is (it is a shared, long-lived local service, not per-build state); otherwise a real {@code
 * IvaldiMain} process is spawned detached -- output to {@code ~/.gimle/ivaldi/ivaldi.log}, pid
 * recorded beside it for {@code gimle:ivaldi-stop} -- on {@code gimle-ivaldi}'s own resolved
 * runtime classpath, the same already-installed-artifact resolution {@link PublishMojo} uses for
 * the CLI. Runs once per invocation, at the execution root (see {@link AbstractGimleRootMojo}).
 * Mirrors {@link SagaMojo} exactly.
 */
@Mojo(name = "ivaldi", threadSafe = true)
public final class IvaldiMojo extends AbstractGimleRootMojo {

  static final Duration STARTUP_TIMEOUT = Duration.ofSeconds(30);

  // 9097: clear of the sibling gimle:* goals' own defaults (store raft 9080 / client 9091,
  // fafnir 9092, muninn 9093, andvari 9094, saga 9096).
  @Parameter(property = "gimle.ivaldi.port", defaultValue = "9097")
  private String port;

  /** Overridable so an out-of-tree project can pin a server build other than this plugin's own. */
  @Parameter(property = "gimle.ivaldi.serverVersion", defaultValue = "${plugin.version}")
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
    IvaldiClient client = new IvaldiClient("http://127.0.0.1:" + port);
    IvaldiServer.Ensured ensured =
        IvaldiServer.ensureRunning(client, this::spawnServer, STARTUP_TIMEOUT, getLog());
    String verb = ensured == IvaldiServer.Ensured.REUSED ? "reusing running" : "started";
    getLog().info(verb + " Ivaldi server -- console: " + client.endpoint() + "/console");
  }

  private Process spawnServer() throws MojoExecutionException {
    String classpath =
        GimleProcesses.resolveRuntimeClasspath(
            "gimle-ivaldi",
            serverVersion,
            remoteRepositories,
            repositorySystemSession,
            repositorySystem);
    return IvaldiServer.spawnDetached(
        IvaldiServer.spawnCommand(GimleProcesses.javaExecutable(), classpath, port), getLog());
  }
}
