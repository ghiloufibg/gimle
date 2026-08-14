package com.gimle.holmgang.cluster;

import com.gimle.holmgang.HolmgangException;
import com.gimle.holmgang.topology.ProcessRole;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * The one {@link GimleProcess} implementation: remembers its own spawn command so {@link #restart}
 * can respawn identically, appending to the same log file so the pre- and post-restart output stays
 * one readable stream.
 */
final class ManagedProcess implements GimleProcess {

  private final ProcessRole role;
  private final String id;
  private final List<String> command;
  private final Path logFile;
  private final String endpoint;
  private volatile Process current;

  ManagedProcess(
      final ProcessRole role,
      final String id,
      final List<String> command,
      final Path logFile,
      final String endpoint) {
    this.role = role;
    this.id = id;
    this.command = List.copyOf(command);
    this.logFile = logFile;
    this.endpoint = endpoint;
    this.current = spawn(ProcessBuilder.Redirect.to(logFile.toFile()));
  }

  private Process spawn(final ProcessBuilder.Redirect output) {
    final ProcessBuilder pb = new ProcessBuilder(command);
    // Never inheritIO(): Surefire/Failsafe's forked-JVM protocol talks to the parent Maven
    // process over this JVM's own stdout, and a child writing to it directly corrupts that
    // channel -- redirect to a file instead.
    pb.redirectErrorStream(true);
    pb.redirectOutput(output);
    try {
      return pb.start();
    } catch (final IOException e) {
      throw new HolmgangException("failed spawning " + role + " " + id, e);
    }
  }

  @Override
  public ProcessRole role() {
    return role;
  }

  @Override
  public String id() {
    return id;
  }

  @Override
  public long pid() {
    return current.pid();
  }

  @Override
  public boolean isAlive() {
    return current.isAlive();
  }

  @Override
  public void kill() {
    current.destroyForcibly();
    awaitExit();
  }

  @Override
  public void killWithDescendants() {
    current.descendants().forEach(ProcessHandle::destroy);
    current.destroyForcibly();
    awaitExit();
  }

  @Override
  public void restart() {
    killWithDescendants();
    current = spawn(ProcessBuilder.Redirect.appendTo(logFile.toFile()));
  }

  @Override
  public Path logFile() {
    return logFile;
  }

  @Override
  public String endpoint() {
    return endpoint;
  }

  Process process() {
    return current;
  }

  private void awaitExit() {
    try {
      current.waitFor();
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new HolmgangException("interrupted awaiting exit of " + role + " " + id, e);
    }
  }
}
