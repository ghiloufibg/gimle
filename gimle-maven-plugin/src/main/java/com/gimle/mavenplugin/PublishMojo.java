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
 * {@code mvn gimle:publish} -- pushes the module jar this project just built to a running artifact
 * registry via a real {@code GimleCli} subprocess ({@code artifact push}), which derives the
 * registry coordinate from the jar's own bundled {@code gimle-module.yaml} rather than from any
 * parameter here.
 *
 * <p>Unlike {@link DeployMojo} and the other spawn-one-process goals, this doesn't extend {@link
 * AbstractGimleMojo}: those all target one specific module of this repo's own reactor and
 * self-filter to it, whereas publishing is the one goal meant to run inside an arbitrary module
 * project that merely depends on this plugin (the {@code gimle-examples} modules, or any
 * out-of-tree module). There is no artifactId it could self-filter to, so it runs wherever it's
 * invoked -- bind it to a phase in the module's own POM, or invoke it with {@code -pl}, rather than
 * bare from a multi-module root.
 *
 * <p>For the same reason the CLI's classpath can't come from {@code
 * ${project.runtimeClasspathElements}} -- that's the calling project's classpath, which has no
 * reason to contain {@code gimle-cli} at all. It's resolved directly against the already-{@code mvn
 * install}ed {@code com.gimle:gimle-cli} artifact via Maven's own dependency resolver, the same way
 * {@link AgentMojo} reaches {@code gimle-worker}, at the version of this plugin itself: a plugin
 * and the CLI it spawns ship from one build, whereas the calling project's own version is unrelated
 * to either.
 */
@Mojo(name = "publish", threadSafe = true)
public final class PublishMojo extends AbstractMojo {

  @Parameter(
      property = "gimle.publish.file",
      defaultValue = "${project.build.directory}/${project.build.finalName}.jar")
  private String file;

  @Parameter(property = "gimle.publish.server", defaultValue = "127.0.0.1:8080")
  private String server;

  /** Overridable so an out-of-tree project can pin a CLI build other than this plugin's own. */
  @Parameter(property = "gimle.publish.cliVersion", defaultValue = "${plugin.version}")
  private String cliVersion;

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
    String cliClasspath =
        GimleProcesses.resolveRuntimeClasspath(
            "gimle-cli", cliVersion, remoteRepositories, repositorySystemSession, repositorySystem);

    List<String> command = new ArrayList<>();
    command.add(GimleProcesses.javaExecutable());
    command.add("-cp");
    command.add(cliClasspath);
    command.add("com.gimle.cli.GimleCli");
    command.add("artifact");
    command.add("push");
    command.add(file);
    command.add("--server");
    command.add(server);

    getLog().info("publishing " + file + " to the registry behind " + server);
    GimleProcesses.runAndWait(command, getLog());
  }
}
