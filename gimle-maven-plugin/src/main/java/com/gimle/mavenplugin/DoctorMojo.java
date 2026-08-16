package com.gimle.mavenplugin;

import java.util.ArrayList;
import java.util.List;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.repository.RemoteRepository;

/**
 * {@code mvn gimle:doctor} -- runs {@code hilmir doctor} against this project's own built jar via a
 * real {@code HilmirMain} subprocess. Like {@link PublishMojo} and unlike every process-launching
 * goal that extends {@link AbstractGimleMojo}, this doesn't self-filter to one fixed reactor
 * artifactId: {@code doctor} is meant to run against *whatever* module invokes it -- any consumer's
 * own reactor module, in-tree or out-of-tree -- not one specific module of this repo's own reactor.
 * So there is no {@code targetArtifactId()} to gate on; bind this goal in the calling project's own
 * {@code pom.xml}, or invoke it with {@code -pl <module>}, the same way {@code gimle:publish}
 * already documents. {@code gimle-hilmir}'s own runtime classpath is resolved by coordinate against
 * the already-{@code mvn install}ed {@code com.gimle:gimle-hilmir} artifact (the same {@link
 * GimleProcesses#resolveRuntimeClasspath} {@code gimle:publish} uses for {@code gimle-cli}), not
 * from {@code ${project.runtimeClasspathElements}} -- the calling project's own classpath has no
 * reason to contain {@code gimle-hilmir} at all.
 */
@Mojo(name = "doctor", threadSafe = true)
public final class DoctorMojo extends AbstractMojo {

  @Parameter(
      property = "gimle.doctor.jar",
      defaultValue = "${project.build.directory}/${project.build.finalName}.jar")
  private String jar;

  @Parameter(property = "gimle.doctor.vessel", defaultValue = "false")
  private boolean vessel;

  /** Unset (the default) skips every cluster-aware check -- only the static catalog runs. */
  @Parameter(property = "gimle.doctor.server")
  private String server;

  @Parameter(property = "gimle.doctor.tenant")
  private String tenant;

  /** Overridable so an out-of-tree project can pin a Hilmir build other than the plugin's own. */
  @Parameter(property = "gimle.doctor.hilmirVersion", defaultValue = "${plugin.version}")
  private String hilmirVersion;

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
    String hilmirClasspath =
        GimleProcesses.resolveRuntimeClasspath(
            "gimle-hilmir",
            hilmirVersion,
            remoteRepositories,
            repositorySystemSession,
            repositorySystem);
    List<String> command =
        buildCommand(GimleProcesses.javaExecutable(), hilmirClasspath, jar, vessel, server, tenant);
    GimleProcesses.runAndWait(command, getLog());
  }

  /**
   * Pure command construction, split out from {@link #execute()} so it's unit-testable without
   * Maven's own parameter-injection machinery -- the seam {@code DoctorMojoTest} exercises
   * directly.
   */
  static List<String> buildCommand(
      String javaExecutable,
      String hilmirClasspath,
      String jar,
      boolean vessel,
      String server,
      String tenant) {
    List<String> command = new ArrayList<>();
    command.add(javaExecutable);
    command.add("-cp");
    command.add(hilmirClasspath);
    command.add("com.gimle.hilmir.HilmirMain");
    command.add("doctor");
    command.add(jar);
    if (vessel) {
      command.add("--vessel");
    }
    if (server != null && !server.isBlank()) {
      command.add("--server");
      command.add(server);
    }
    if (tenant != null && !tenant.isBlank()) {
      command.add("--tenant");
      command.add(tenant);
    }
    return command;
  }
}
