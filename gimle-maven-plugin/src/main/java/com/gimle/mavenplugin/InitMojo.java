package com.gimle.mavenplugin;

import java.io.File;
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
 * {@code mvn gimle:init} -- runs {@code hilmir init} against this project's own built jar via a
 * real {@code HilmirMain} subprocess, writing {@code gimle-module.yaml}/{@code deployment.yaml}
 * into the project directory. Same shape and same reasoning as {@link DoctorMojo} for not extending
 * {@link AbstractGimleMojo}: this needs to run against whatever module invokes it, not one fixed
 * reactor artifactId of this repo's own.
 */
@Mojo(name = "init", threadSafe = true)
public final class InitMojo extends AbstractMojo {

  @Parameter(
      property = "gimle.init.jar",
      defaultValue = "${project.build.directory}/${project.build.finalName}.jar")
  private String jar;

  /**
   * Where the generated files land, this project's own directory by default. Declared as a {@link
   * File}, not a {@code String}: Maven evaluates a default value that is exactly one expression to
   * that expression's own type, and {@code ${project.basedir}} is a {@code File} -- a {@code
   * String} field is simply left null, which passes no output directory on at all and lets the
   * generated files fall back to landing beside the inspected jar, inside {@code target/}, where
   * the next {@code mvn clean} deletes them.
   */
  @Parameter(property = "gimle.init.outDir", defaultValue = "${project.basedir}")
  private File outDir;

  @Parameter(property = "gimle.init.hilmirVersion", defaultValue = "${plugin.version}")
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
        buildCommand(
            GimleProcesses.javaExecutable(),
            hilmirClasspath,
            jar,
            outDir == null ? null : outDir.getAbsolutePath());
    GimleProcesses.runAndWait(command, getLog());
  }

  /**
   * Pure command construction, split out from {@link #execute()} so it's unit-testable without
   * Maven's own parameter-injection machinery -- the seam {@code InitMojoTest} exercises directly.
   */
  static List<String> buildCommand(
      String javaExecutable, String hilmirClasspath, String jar, String outDir) {
    List<String> command = new ArrayList<>();
    command.add(javaExecutable);
    command.add("-cp");
    command.add(hilmirClasspath);
    command.add("com.gimle.hilmir.HilmirMain");
    command.add("init");
    command.add(jar);
    if (outDir != null && !outDir.isBlank()) {
      command.add("--out-dir");
      command.add(outDir);
    }
    return command;
  }
}
