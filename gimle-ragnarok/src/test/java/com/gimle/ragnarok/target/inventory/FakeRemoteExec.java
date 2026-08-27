package com.gimle.ragnarok.target.inventory;

import com.gimle.hilmir.remote.RemoteExec;
import com.gimle.hilmir.remote.ResolvedSshTarget;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

/**
 * A {@link RemoteExec} that runs every command through a real local shell instead of over SSH --
 * the scripts {@link SshManagedProcess} generates are plain POSIX shell with no SSH-specific
 * content, so running them locally exercises the exact same script-generation and output-parsing
 * logic a real SSH round trip would, without needing a real SSH server for a unit test. Output is
 * prefixed with {@code target.machineName()} the identical way {@code RemoteOutput.relayPrefixed}
 * prefixes a real SSH session's output, so {@code SshManagedProcess}'s own prefix-stripping is
 * exercised for real too.
 */
final class FakeRemoteExec implements RemoteExec {

  @Override
  public int exec(
      final ResolvedSshTarget target, final List<String> remoteCommand, final PrintStream out) {
    return run(target, remoteCommand, out);
  }

  @Override
  public int execRaw(
      final ResolvedSshTarget target, final List<String> remoteCommand, final PrintStream out) {
    return run(target, remoteCommand, out);
  }

  @Override
  public void putFile(
      final ResolvedSshTarget target, final Path localFile, final String remotePath) {
    throw new UnsupportedOperationException("not needed by SshManagedProcess");
  }

  @Override
  public void pinHostKey(final ResolvedSshTarget target, final Path knownHostsFile) {
    // No-op: this fake never actually connects anywhere.
  }

  private int run(
      final ResolvedSshTarget target, final List<String> command, final PrintStream out) {
    final ProcessBuilder processBuilder = new ProcessBuilder(command);
    processBuilder.redirectErrorStream(true);
    try {
      final Process process = processBuilder.start();
      try (var reader =
          new java.io.BufferedReader(
              new java.io.InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
        String line;
        while ((line = reader.readLine()) != null) {
          out.println("[" + target.machineName() + "] " + line);
        }
      }
      return process.waitFor();
    } catch (final IOException e) {
      throw new RuntimeException("failed running fake remote command: " + command, e);
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new RuntimeException("interrupted running fake remote command: " + command, e);
    }
  }
}
