package com.gimle.hilmir.remote;

import com.gimle.hilmir.HilmirException;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The real {@link RemoteExec}: shells out to the operator's own already-installed {@code ssh}/
 * {@code scp} via {@link ProcessBuilder}, the same pattern {@code
 * com.gimle.hilmir.launch.MachineLauncher#spawn}/{@code runOneShot} already use for local process
 * spawning -- no SSH library dependency, no new JPMS {@code requires}. This is also literally how
 * Ansible's default {@code ssh} connection plugin works.
 *
 * <p><b>v1 explicitly has no host-key verification</b> ({@code StrictHostKeyChecking=no}, {@code
 * UserKnownHostsFile=/dev/null}) -- {@link RemoteDispatch} prints a one-time warning about this
 * before dispatching. {@code BatchMode=yes}/{@code ConnectTimeout=10} are not a security feature:
 * without them, a target whose key auth isn't fully set up falls back to an interactive password
 * prompt with no controlling TTY to answer it, silently stalling that machine's dispatch thread
 * forever instead of failing fast. Credentials are never handled here at all -- auth is entirely
 * the operator's own {@code ssh} identity (agent or default key).
 */
final class SshProcessExec implements RemoteExec {

  private static final List<String> COMMON_SSH_FLAGS =
      List.of(
          "-o", "StrictHostKeyChecking=no",
          "-o", "UserKnownHostsFile=/dev/null",
          "-o", "BatchMode=yes",
          "-o", "ConnectTimeout=10");

  @Override
  public int exec(
      final ResolvedSshTarget target, final List<String> remoteCommand, final PrintStream out) {
    return runAndRelay(sshCommand(target, remoteCommand), target.machineName(), out);
  }

  @Override
  public void putFile(
      final ResolvedSshTarget target, final Path localFile, final String remotePath) {
    runCapturing(
        scpCommand(target, localFile, remotePath),
        "copying " + localFile + " to " + target.machineName());
  }

  /**
   * The full {@code ssh ... user@host "cd ... && ..."} argv -- a pure function, easy to unit test.
   */
  static List<String> sshCommand(final ResolvedSshTarget target, final List<String> remoteCommand) {
    final List<String> command = new ArrayList<>();
    command.add("ssh");
    command.addAll(COMMON_SSH_FLAGS);
    target.port().ifPresent(p -> command.addAll(List.of("-p", String.valueOf(p))));
    target.identityFile().ifPresent(f -> command.addAll(List.of("-i", f)));
    command.add(destination(target));
    command.add(remoteShellCommand(target, remoteCommand));
    return command;
  }

  /** The full {@code scp ... localFile user@host:remotePath} argv -- a pure function too. */
  static List<String> scpCommand(
      final ResolvedSshTarget target, final Path localFile, final String remotePath) {
    final List<String> command = new ArrayList<>();
    command.add("scp");
    command.addAll(COMMON_SSH_FLAGS);
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
    final StringBuilder shell = new StringBuilder("cd ").append(shellQuote(target.installDir()));
    shell.append(" && ");
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

  /** {@code ssh}/{@code scp} themselves, never the shell-quoted remote command -- kept short. */
  private static String describe(final List<String> command) {
    return command.isEmpty() ? "(empty command)" : command.get(0);
  }
}
