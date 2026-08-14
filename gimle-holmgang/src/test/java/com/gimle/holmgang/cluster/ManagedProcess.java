package com.gimle.holmgang.cluster;

import com.gimle.holmgang.HolmgangException;
import com.gimle.holmgang.topology.ProcessRole;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The one {@link GimleProcess} implementation: remembers its own spawn command so {@link #restart}
 * can respawn identically, appending to the same log file so the pre- and post-restart output stays
 * one readable stream. Exit callbacks are re-armed on every respawn, and {@link #exitWasExpected}
 * is raised before any harness-initiated kill so a watcher can tell scenario action from genuine
 * crash.
 */
final class ManagedProcess implements GimleProcess {

  private final ProcessRole role;
  private final String id;
  // The caller's original command, rewritten by JavaArgFile.rewrite into "java @argfile" form --
  // see that class's own javadoc for why every spawn here needs it, not just AGENT's.
  private final List<String> command;
  private final Path logFile;
  private final String endpoint;
  private final CopyOnWriteArrayList<Runnable> exitCallbacks = new CopyOnWriteArrayList<>();
  private volatile Process current;
  private volatile boolean exitExpected;

  ManagedProcess(
      final ProcessRole role,
      final String id,
      final List<String> command,
      final Path logFile,
      final String endpoint) {
    this.role = role;
    this.id = id;
    this.command = JavaArgFile.rewrite(command, Path.of(logFile + ".args"));
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
      final Process process = pb.start();
      process.onExit().thenRun(() -> exitCallbacks.forEach(Runnable::run));
      return process;
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
    exitExpected = true;
    current.destroyForcibly();
    awaitExit();
  }

  @Override
  public void killWithDescendants() {
    exitExpected = true;
    current.descendants().forEach(ProcessHandle::destroy);
    current.destroyForcibly();
    awaitExit();
  }

  @Override
  public void restart() {
    killWithDescendants();
    current = spawn(ProcessBuilder.Redirect.appendTo(logFile.toFile()));
    exitExpected = false;
  }

  @Override
  public void onExit(final Runnable callback) {
    exitCallbacks.add(callback);
  }

  @Override
  public boolean exitWasExpected() {
    return exitExpected;
  }

  @Override
  public Path logFile() {
    return logFile;
  }

  @Override
  public String endpoint() {
    return endpoint;
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
