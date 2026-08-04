package com.gimle.mavenplugin;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.apache.maven.plugin.MojoExecutionException;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.collection.CollectRequest;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.resolution.ArtifactResult;
import org.eclipse.aether.resolution.DependencyRequest;
import org.eclipse.aether.resolution.DependencyResolutionException;

/**
 * Small process/classpath-resolution mechanics shared by more than one {@code gimle:*} goal --
 * previously duplicated between {@link AbstractGimleMojo} and {@link DocsMojo} ({@code
 * javaExecutable()}) and unique to {@link AgentMojo} ({@code resolveWorkerClasspath()}, generalized
 * here to any artifactId so {@link BootstrapMojo} can reuse it for the six modules it spawns).
 */
final class GimleProcesses {

  private GimleProcesses() {}

  /**
   * Resolves the real java launcher this Maven process itself is running under, mirroring {@code
   * gimle-agent}'s own {@code ResourceLimitEnforcementTest.javaExecutable()} -- so a spawned child
   * runs on the exact same JDK the developer already has active for this build.
   */
  static String javaExecutable() {
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

  /**
   * Resolves {@code artifactId}'s runtime classpath directly against its already-{@code mvn
   * install}ed jar via Maven's own dependency resolver, independent of reactor build order --
   * {@link AgentMojo}'s original {@code resolveWorkerClasspath()}, generalized to any module rather
   * than hardcoding {@code gimle-worker}.
   */
  static String resolveRuntimeClasspath(
      String artifactId,
      String projectVersion,
      List<RemoteRepository> remoteRepositories,
      RepositorySystemSession repositorySystemSession,
      RepositorySystem repositorySystem)
      throws MojoExecutionException {
    Artifact artifact = new DefaultArtifact("com.gimle", artifactId, "jar", projectVersion);
    CollectRequest collectRequest = new CollectRequest();
    collectRequest.setRoot(new Dependency(artifact, "runtime"));
    collectRequest.setRepositories(remoteRepositories);
    DependencyRequest dependencyRequest = new DependencyRequest(collectRequest, null);

    List<ArtifactResult> results;
    try {
      results =
          repositorySystem
              .resolveDependencies(repositorySystemSession, dependencyRequest)
              .getArtifactResults();
    } catch (DependencyResolutionException e) {
      throw new MojoExecutionException(
          "failed to resolve "
              + artifactId
              + "'s runtime classpath -- has `mvn install` been run"
              + " yet?",
          e);
    }

    List<String> paths = new ArrayList<>();
    for (ArtifactResult result : results) {
      paths.add(result.getArtifact().getFile().getAbsolutePath());
    }
    return String.join(File.pathSeparator, paths);
  }
}
