package com.gimle.hilmir.remote;

import com.gimle.hilmir.HilmirException;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * The real {@link RemoteExec}: shells out to the operator's own already-installed {@code ssh}/
 * {@code scp}/{@code ssh-keyscan}/{@code ssh-keygen} via {@link ProcessBuilder}, the same pattern
 * {@code com.gimle.hilmir.launch.MachineLauncher#spawn}/{@code runOneShot} already use for local
 * process spawning -- no SSH library dependency, no new JPMS {@code requires}. This is also
 * literally how Ansible's default {@code ssh} connection plugin works.
 *
 * <p>Host-key verification is real: {@code StrictHostKeyChecking=yes} against a per-topology {@code
 * knownHostsFile} (never the operator's own global one), populated by {@link #pinHostKey} before
 * any {@code ssh}/{@code scp} call runs against a given target -- see that method's own javadoc.
 * Credentials are never handled here at all -- auth is entirely the operator's own {@code ssh}
 * identity (agent or default key). {@code BatchMode=yes}/{@code ConnectTimeout=10} are not a
 * security feature: without them, a target whose key auth isn't fully set up falls back to an
 * interactive password prompt with no controlling TTY to answer it, silently stalling that
 * machine's dispatch thread forever instead of failing fast.
 *
 * <p>Public rather than package-private: {@code gimle-ragnarok}'s own SSH-backed {@code
 * ClusterTarget} constructs this directly to run arbitrary process-control commands (kill/restart)
 * over the identical hardened transport, rather than reimplementing host-key pinning/quoting/
 * timeouts a second time.
 */
public final class SshProcessExec implements RemoteExec {

  private static final Duration FINGERPRINT_TIMEOUT = Duration.ofSeconds(10);

  private final List<String> commonFlags;
  private final Path knownHostsFile;

  public SshProcessExec(final Path knownHostsFile) {
    this.knownHostsFile = knownHostsFile;
    this.commonFlags =
        List.of(
            "-o", "StrictHostKeyChecking=yes",
            "-o", "UserKnownHostsFile=" + knownHostsFile,
            "-o", "BatchMode=yes",
            "-o", "ConnectTimeout=10");
  }

  @Override
  public int exec(
      final ResolvedSshTarget target, final List<String> remoteCommand, final PrintStream out) {
    return runAndRelay(sshCommand(target, remoteCommand), target.machineName(), out);
  }

  @Override
  public int execRaw(
      final ResolvedSshTarget target, final List<String> remoteCommand, final PrintStream out) {
    return runAndRelay(sshCommandRaw(target, remoteCommand), target.machineName(), out);
  }

  @Override
  public void putFile(
      final ResolvedSshTarget target, final Path localFile, final String remotePath) {
    runCapturing(
        scpCommand(target, localFile, remotePath),
        "copying " + localFile + " to " + target.machineName());
  }

  /**
   * {@code ssh-keyscan} queries {@code target}'s host key directly -- it does its own connection,
   * entirely independent of (and unauthenticated by) the {@code StrictHostKeyChecking} trust path
   * every other method here relies on, so there's no chicken-and-egg problem running it before
   * {@code knownHostsFile} has anything in it yet. A declared {@link
   * ResolvedSshTarget#hostKeyFingerprint()} is checked against every key algorithm the scan
   * returned (one match is enough); with none declared, whatever was scanned is trusted outright
   * (trust-on-first-use). Re-scans and re-verifies on every call rather than skipping a
   * already-pinned target -- for a fingerprint-pinned target that's the whole point (catching a
   * rotated key on a later run, not just the first one); for an unpinned target it's a documented,
   * simpler v1 behavior (always trust the freshest scan) rather than real persisted TOFU history.
   */
  @Override
  public void pinHostKey(final ResolvedSshTarget target, final Path knownHostsFile) {
    final List<String> scanCommand = new ArrayList<>();
    scanCommand.add("ssh-keyscan");
    target.port().ifPresent(p -> scanCommand.addAll(List.of("-p", String.valueOf(p))));
    scanCommand.add(target.host());
    final String scanned =
        runCapturingOutput(scanCommand, "scanning SSH host key for " + target.machineName());
    final List<String> keyLines =
        scanned.lines().filter(line -> !line.isBlank() && !line.startsWith("#")).toList();
    if (keyLines.isEmpty()) {
      throw new HilmirException(
          "could not scan an SSH host key for "
              + target.machineName()
              + " ("
              + target.host()
              + ") -- is it reachable on port "
              + target.port().map(String::valueOf).orElse("22")
              + "?");
    }
    target
        .hostKeyFingerprint()
        .ifPresent(expected -> verifyFingerprint(target, keyLines, expected));
    appendKnownHosts(knownHostsFile, keyLines);
  }

  private static void verifyFingerprint(
      final ResolvedSshTarget target, final List<String> keyLines, final String expected) {
    final boolean matched =
        keyLines.stream().anyMatch(line -> fingerprintOf(line).equals(expected));
    if (!matched) {
      throw new HilmirException(
          "SSH host key fingerprint mismatch for "
              + target.machineName()
              + " ("
              + target.host()
              + "): expected "
              + expected
              + ", but none of the scanned key(s) matched -- refusing to trust this host (a"
              + " rotated key, a stale pinned fingerprint, or a possible man-in-the-middle)");
    }
  }

  /**
   * Runs {@code ssh-keygen -lf} against one {@code known_hosts}-format line, e.g. {@code
   * SHA256:...}.
   */
  private static String fingerprintOf(final String knownHostsLine) {
    Path tempFile = null;
    try {
      tempFile = Files.createTempFile("hilmir-hostkey-", ".pub");
      Files.writeString(tempFile, knownHostsLine + "\n", StandardCharsets.UTF_8);
      final ProcessBuilder processBuilder =
          new ProcessBuilder("ssh-keygen", "-lf", tempFile.toString());
      processBuilder.redirectErrorStream(true);
      final Process process = processBuilder.start();
      final String output =
          new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
      if (!process.waitFor(FINGERPRINT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
        process.destroyForcibly();
        throw new HilmirException(
            "ssh-keygen -lf timed out after " + FINGERPRINT_TIMEOUT + " computing a fingerprint");
      }
      final String[] parts = output.trim().split("\\s+");
      return parts.length >= 2 ? parts[1] : "";
    } catch (final IOException e) {
      throw new HilmirException("failed computing an SSH host key fingerprint", e);
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new HilmirException("interrupted computing an SSH host key fingerprint", e);
    } finally {
      if (tempFile != null) {
        try {
          Files.deleteIfExists(tempFile);
        } catch (final IOException ignored) {
          // Best-effort cleanup only -- a leftover temp file is not worth failing pinning over.
        }
      }
    }
  }

  private static void appendKnownHosts(final Path knownHostsFile, final List<String> keyLines) {
    try {
      final Set<String> existing = new LinkedHashSet<>();
      if (Files.exists(knownHostsFile)) {
        existing.addAll(Files.readAllLines(knownHostsFile, StandardCharsets.UTF_8));
      }
      final List<String> toAppend =
          keyLines.stream().filter(line -> !existing.contains(line)).toList();
      if (toAppend.isEmpty()) {
        return;
      }
      final Path parent = knownHostsFile.getParent();
      if (parent != null) {
        Files.createDirectories(parent);
      }
      Files.writeString(
          knownHostsFile,
          String.join("\n", toAppend) + "\n",
          StandardCharsets.UTF_8,
          StandardOpenOption.CREATE,
          StandardOpenOption.APPEND);
    } catch (final IOException e) {
      throw new HilmirException("failed writing known_hosts file " + knownHostsFile, e);
    }
  }

  /**
   * The full {@code ssh ... user@host "cd ... && ..."} argv -- a pure function, easy to unit test.
   */
  List<String> sshCommand(final ResolvedSshTarget target, final List<String> remoteCommand) {
    final List<String> command = baseSshCommand(target);
    command.add(remoteShellCommand(target, remoteCommand));
    return command;
  }

  /** The same as {@link #sshCommand}, but with no {@code cd} into {@code target.installDir()}. */
  List<String> sshCommandRaw(final ResolvedSshTarget target, final List<String> remoteCommand) {
    final List<String> command = baseSshCommand(target);
    command.add(quoteAndJoin(remoteCommand));
    return command;
  }

  private List<String> baseSshCommand(final ResolvedSshTarget target) {
    final List<String> command = new ArrayList<>();
    command.add("ssh");
    command.addAll(commonFlags);
    target.port().ifPresent(p -> command.addAll(List.of("-p", String.valueOf(p))));
    target.identityFile().ifPresent(f -> command.addAll(List.of("-i", f)));
    command.add(destination(target));
    return command;
  }

  /** The full {@code scp ... localFile user@host:remotePath} argv -- a pure function too. */
  List<String> scpCommand(
      final ResolvedSshTarget target, final Path localFile, final String remotePath) {
    final List<String> command = new ArrayList<>();
    command.add("scp");
    command.addAll(commonFlags);
    // scp's port flag is uppercase -P (lowercase -p means "preserve file attributes" instead --
    // unlike ssh, whose port flag is lowercase -p).
    target.port().ifPresent(p -> command.addAll(List.of("-P", String.valueOf(p))));
    target.identityFile().ifPresent(f -> command.addAll(List.of("-i", f)));
    command.add(localFile.toString());
    command.add(destination(target) + ":" + remotePath);
    return command;
  }

  private static String destination(final ResolvedSshTarget target) {
    return target.user().map(u -> u + "@" + target.host()).orElse(target.host());
  }

  /**
   * {@code cd '<installDir>' && <quoted argv>}, as one shell string: ssh's own remote command is
   * always interpreted by the remote login shell, and {@code cd}-ing first is what keeps a data
   * root the remote {@code hilmir} resolves relative to its own CWD landing in the same place on
   * every verb regardless of the SSH session's own default directory (see {@link RemoteExec#exec}).
   */
  private static String remoteShellCommand(
      final ResolvedSshTarget target, final List<String> remoteCommand) {
    return "cd " + shellQuote(target.installDir()) + " && " + quoteAndJoin(remoteCommand);
  }

  private static String quoteAndJoin(final List<String> remoteCommand) {
    final StringBuilder shell = new StringBuilder();
    for (int i = 0; i < remoteCommand.size(); i++) {
      if (i > 0) {
        shell.append(' ');
      }
      shell.append(shellQuote(remoteCommand.get(i)));
    }
    return shell.toString();
  }

  /** POSIX single-quoting: close the quote, escape a literal quote, reopen it. */
  private static String shellQuote(final String token) {
    return "'" + token.replace("'", "'\\''") + "'";
  }

  private static int runAndRelay(
      final List<String> command, final String machineName, final PrintStream out) {
    final ProcessBuilder processBuilder = new ProcessBuilder(command);
    processBuilder.redirectErrorStream(true);
    try {
      final Process process = processBuilder.start();
      final Thread relay = RemoteOutput.relayPrefixed(process.getInputStream(), machineName, out);
      final int exitCode = process.waitFor();
      relay.join();
      return exitCode;
    } catch (final IOException e) {
      throw new HilmirException("failed to run " + describe(command) + " for " + machineName, e);
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new HilmirException("interrupted while running " + describe(command), e);
    }
  }

  /** Buffers output instead of relaying it live, folding it into the exception on failure. */
  private static void runCapturing(final List<String> command, final String description) {
    final ProcessBuilder processBuilder = new ProcessBuilder(command);
    processBuilder.redirectErrorStream(true);
    final String output;
    final int exitCode;
    try {
      final Process process = processBuilder.start();
      output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
      exitCode = process.waitFor();
    } catch (final IOException e) {
      throw new HilmirException(description + " could not start", e);
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new HilmirException(description + " was interrupted", e);
    }
    if (exitCode != 0) {
      throw new HilmirException(
          description + " failed with exit code " + exitCode + "; captured output:\n" + output);
    }
  }

  /**
   * Like {@link #runCapturing}, but returns the captured output instead of throwing on a nonzero
   * exit -- {@code ssh-keyscan}'s own exit-code semantics aren't as strict as {@code scp}/{@code
   * ssh}'s, so an empty-vs-nonempty result is the more reliable success signal.
   */
  private static String runCapturingOutput(final List<String> command, final String description) {
    final ProcessBuilder processBuilder = new ProcessBuilder(command);
    processBuilder.redirectErrorStream(false);
    try {
      final Process process = processBuilder.start();
      final String output =
          new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
      if (!process.waitFor(FINGERPRINT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
        process.destroyForcibly();
        throw new HilmirException(description + " timed out after " + FINGERPRINT_TIMEOUT);
      }
      return output;
    } catch (final IOException e) {
      throw new HilmirException(description + " could not start", e);
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new HilmirException(description + " was interrupted", e);
    }
  }

  /** {@code ssh}/{@code scp} themselves, never the shell-quoted remote command -- kept short. */
  private static String describe(final List<String> command) {
    return command.isEmpty() ? "(empty command)" : command.get(0);
  }
}
