package com.gimle.ragnarok.target.inventory;

import com.gimle.hilmir.remote.RemoteExec;
import com.gimle.hilmir.remote.ResolvedSshTarget;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A {@link RemoteExec} that never actually runs anything -- unlike {@link FakeRemoteExec}, which
 * genuinely executes every command via a real local {@code ProcessBuilder} (fine for process
 * kill/restart, unsafe for {@code iptables}, which would attempt to mutate the test runner's own
 * firewall and fail outright without root). Records every {@link #execRaw} call's exact command for
 * assertion instead.
 */
final class RecordingRemoteExec implements RemoteExec {

  record Call(String machineName, List<String> command) {}

  private final List<Call> calls = new CopyOnWriteArrayList<>();
  private volatile int nextExitCode = 0;

  @Override
  public int exec(
      final ResolvedSshTarget target, final List<String> remoteCommand, final PrintStream out) {
    calls.add(new Call(target.machineName(), remoteCommand));
    return nextExitCode;
  }

  @Override
  public int execRaw(
      final ResolvedSshTarget target, final List<String> remoteCommand, final PrintStream out) {
    calls.add(new Call(target.machineName(), remoteCommand));
    return nextExitCode;
  }

  @Override
  public void putFile(
      final ResolvedSshTarget target, final Path localFile, final String remotePath) {
    throw new UnsupportedOperationException("not needed by SshNetworkFaultInjector");
  }

  @Override
  public void pinHostKey(final ResolvedSshTarget target, final Path knownHostsFile) {
    // No-op: this fake never actually connects anywhere.
  }

  List<Call> calls() {
    return List.copyOf(calls);
  }

  void clearCalls() {
    calls.clear();
  }

  void nextExitCode(final int exitCode) {
    this.nextExitCode = exitCode;
  }
}
