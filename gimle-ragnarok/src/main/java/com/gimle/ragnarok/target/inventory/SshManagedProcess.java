package com.gimle.ragnarok.target.inventory;

import com.gimle.hilmir.HilmirException;
import com.gimle.hilmir.remote.RemoteExec;
import com.gimle.hilmir.remote.ResolvedSshTarget;
import com.gimle.ragnarok.RagnarokException;
import com.gimle.ragnarok.target.GimleProcess;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * A {@link GimleProcess} controlled purely over SSH via {@link RemoteExec} -- no remote agent or
 * {@code hilmir} binary invoked, matching what {@code Fenrir.bounce()} actually needs (synchronous,
 * single-process kill-then-restart), not {@code RemoteDispatch}'s "ask the remote supervisor to
 * restart itself" pattern. Every remote check reads {@code role.pidFile()} fresh rather than
 * caching a pid locally, so a {@link #restart()}'s new pid is picked up by {@link #pid()}/{@link
 * #isAlive()} with no callback plumbing needed.
 *
 * <p>{@link #onExit} has no OS-level future to attach to over SSH (unlike a local {@code
 * Process.onExit()}) -- callbacks are served by a lazily-started background poller that fires on an
 * observed alive-to-dead transition, started only once something actually registers a callback.
 */
final class SshManagedProcess implements GimleProcess {

  private static final Duration KILL_TIMEOUT = Duration.ofSeconds(15);
  private static final Duration RESTART_TIMEOUT = Duration.ofSeconds(15);
  private static final Duration POLL_INTERVAL = Duration.ofMillis(200);
  private static final Duration EXIT_POLL_INTERVAL = Duration.ofSeconds(2);

  private final RemoteExec remoteExec;
  private final ResolvedSshTarget target;
  private final ManagedRoleSpec role;
  private final String endpoint;

  private final CopyOnWriteArrayList<Runnable> exitCallbacks = new CopyOnWriteArrayList<>();
  private volatile boolean exitExpected;
  private volatile ScheduledExecutorService poller;

  SshManagedProcess(
      final RemoteExec remoteExec,
      final ResolvedSshTarget target,
      final ManagedRoleSpec role,
      final String endpoint) {
    this.remoteExec = remoteExec;
    this.target = target;
    this.role = role;
    this.endpoint = endpoint;
  }

  @Override
  public String role() {
    return role.id().replaceFirst("-\\d+$", "").toUpperCase(Locale.ROOT);
  }

  @Override
  public String id() {
    return role.id();
  }

  @Override
  public long pid() {
    return readPid().orElse(-1L);
  }

  @Override
  public boolean isAlive() {
    return runScript(aliveScript()) == 0;
  }

  @Override
  public void kill() {
    exitExpected = true;
    runScript(killScript());
    awaitDead();
  }

  @Override
  public void killWithDescendants() {
    exitExpected = true;
    runScript(killWithDescendantsScript());
    awaitDead();
  }

  @Override
  public void restart() {
    runScript(restartScript());
    awaitAlive();
    exitExpected = false;
  }

  @Override
  public void onExit(final Runnable callback) {
    exitCallbacks.add(callback);
    startPollerIfNeeded();
  }

  @Override
  public boolean exitWasExpected() {
    return exitExpected;
  }

  @Override
  public Path logFile() {
    return role.logFile();
  }

  @Override
  public String endpoint() {
    return endpoint;
  }

  /**
   * Stops the background exit poller, if one was ever started. Called by the owning target's own
   * close().
   */
  void close() {
    final ScheduledExecutorService current = poller;
    if (current != null) {
      current.shutdownNow();
    }
  }

  private synchronized void startPollerIfNeeded() {
    if (poller != null) {
      return;
    }
    final ScheduledExecutorService executor =
        Executors.newSingleThreadScheduledExecutor(
            r -> {
              final Thread t = new Thread(r, "ssh-exit-poll-" + role.id());
              t.setDaemon(true);
              return t;
            });
    final boolean[] lastKnownAlive = {isAlive()};
    executor.scheduleWithFixedDelay(
        () -> {
          final boolean alive = isAlive();
          if (lastKnownAlive[0] && !alive) {
            exitCallbacks.forEach(Runnable::run);
          }
          lastKnownAlive[0] = alive;
        },
        EXIT_POLL_INTERVAL.toMillis(),
        EXIT_POLL_INTERVAL.toMillis(),
        TimeUnit.MILLISECONDS);
    poller = executor;
  }

  private Optional<Long> readPid() {
    final CapturedResult result =
        captureScript("cat " + shellQuote(role.pidFile().toString()) + " 2>/dev/null");
    if (result.exitCode() != 0 || result.output().isBlank()) {
      return Optional.empty();
    }
    try {
      return Optional.of(Long.parseLong(result.output().trim()));
    } catch (final NumberFormatException e) {
      return Optional.empty();
    }
  }

  private String aliveScript() {
    return "pid=$(cat "
        + shellQuote(role.pidFile().toString())
        + " 2>/dev/null) && [ -n \"$pid\" ] && kill -0 \"$pid\" 2>/dev/null";
  }

  private String killScript() {
    return "pid=$(cat "
        + shellQuote(role.pidFile().toString())
        + " 2>/dev/null); [ -n \"$pid\" ] && kill -9 \"$pid\" 2>/dev/null; true";
  }

  private String killWithDescendantsScript() {
    return "pid=$(cat "
        + shellQuote(role.pidFile().toString())
        + " 2>/dev/null); [ -n \"$pid\" ] && { pkill -9 -P \"$pid\" 2>/dev/null; kill -9 \"$pid\""
        + " 2>/dev/null; }; true";
  }

  private String restartScript() {
    final StringBuilder cmd = new StringBuilder();
    for (final String token : role.command()) {
      if (cmd.length() > 0) {
        cmd.append(' ');
      }
      cmd.append(shellQuote(token));
    }
    // A fresh machine has no reason to already carry the pidFile's/logFile's own parent
    // directories -- an operator's inventory document names them, it doesn't provision them. A
    // synchronous ";" (not "&&", which would fold into the backgrounded list below and make `$!`
    // resolve to a subshell's own pid instead of the spawned command's) keeps the background job
    // boundary exactly where it was before this mkdir existed.
    return "mkdir -p "
        + shellQuote(parentOf(role.logFile()))
        + " "
        + shellQuote(parentOf(role.pidFile()))
        + "; nohup "
        + cmd
        + " >> "
        + shellQuote(role.logFile().toString())
        + " 2>&1 & echo $! > "
        + shellQuote(role.pidFile().toString());
  }

  private static String parentOf(final Path file) {
    final Path parent = file.toAbsolutePath().getParent();
    return parent == null ? "." : parent.toString();
  }

  private void awaitDead() {
    awaitCondition(() -> !isAlive(), KILL_TIMEOUT, "process " + role.id() + " did not die");
  }

  private void awaitAlive() {
    awaitCondition(
        this::isAlive, RESTART_TIMEOUT, "process " + role.id() + " did not come back up");
  }

  private void awaitCondition(
      final BooleanSupplier condition, final Duration timeout, final String failureMessage) {
    final long deadline = System.nanoTime() + timeout.toNanos();
    while (System.nanoTime() < deadline) {
      if (condition.getAsBoolean()) {
        return;
      }
      sleep(POLL_INTERVAL.toMillis());
    }
    if (!condition.getAsBoolean()) {
      throw new RagnarokException(failureMessage + " within " + timeout);
    }
  }

  private static void sleep(final long millis) {
    try {
      Thread.sleep(millis);
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new RagnarokException("interrupted waiting on SSH-managed process", e);
    }
  }

  private int runScript(final String script) {
    return captureScript(script).exitCode();
  }

  private record CapturedResult(int exitCode, String output) {}

  private CapturedResult captureScript(final String script) {
    final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    final int exitCode;
    try (PrintStream capture = new PrintStream(buffer, true, StandardCharsets.UTF_8)) {
      exitCode = remoteExec.execRaw(target, List.of("sh", "-c", script), capture);
    } catch (final HilmirException e) {
      throw new RagnarokException("SSH command failed against " + target.machineName(), e);
    }
    final String prefix = "[" + target.machineName() + "] ";
    final StringBuilder output = new StringBuilder();
    for (final String line : buffer.toString(StandardCharsets.UTF_8).split("\n", -1)) {
      if (line.isEmpty()) {
        continue;
      }
      output.append(line.startsWith(prefix) ? line.substring(prefix.length()) : line).append('\n');
    }
    return new CapturedResult(exitCode, output.toString());
  }

  /** POSIX single-quoting: close the quote, escape a literal quote, reopen it. */
  private static String shellQuote(final String token) {
    return "'" + token.replace("'", "'\\''") + "'";
  }
}
