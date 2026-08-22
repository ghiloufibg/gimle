package com.gimle.hilmir.remote;

import com.gimle.hilmir.HilmirException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A scriptable, thread-safe {@link RemoteExec} fake: records every {@code exec}/{@code execRaw}/
 * {@code putFile}/{@code pinHostKey} call it receives (concurrently, since {@link RemoteDispatch}
 * fans out over one virtual thread per machine) and lets a test script a per-machine exit code or
 * thrown failure -- mirrors {@code com.gimle.hilmir.launch.LaunchTestSupport}'s role for {@code
 * MachineLauncher}'s own tests, but for the SSH transport seam instead of a real spawned process.
 *
 * <p>Defaults are chosen so a test that doesn't care about provisioning/pinning sees the "already
 * set up" shape: {@link #execRaw} defaults to exit {@code 0} (a {@code test -x bin/hilmir} probe
 * reporting the binary already present, so no provisioning runs), and {@link #pinHostKey} never
 * throws unless scripted to.
 */
final class FakeRemoteExec implements RemoteExec {

  record ExecCall(String machineName, List<String> command) {}

  record ExecRawCall(String machineName, List<String> command) {}

  record PutFileCall(String machineName, Path localFile, String remotePath) {}

  private final Map<String, Integer> exitCodeByMachine = new ConcurrentHashMap<>();
  private final Map<String, Integer> execRawExitCodeByMachine = new ConcurrentHashMap<>();
  private final Map<String, RuntimeException> failureByMachine = new ConcurrentHashMap<>();
  private final Set<String> hostKeyMismatchMachines = ConcurrentHashMap.newKeySet();
  private final List<ExecCall> execCalls = new CopyOnWriteArrayList<>();
  private final List<ExecRawCall> execRawCalls = new CopyOnWriteArrayList<>();
  private final List<PutFileCall> putFileCalls = new CopyOnWriteArrayList<>();
  private final List<String> pinHostKeyCalls = new CopyOnWriteArrayList<>();

  void exitCodeFor(final String machineName, final int code) {
    exitCodeByMachine.put(machineName, code);
  }

  /**
   * Scripts {@link #execRaw}'s exit code for {@code machineName} -- e.g. {@code 1} to simulate a
   * missing {@code bin/hilmir} (triggering provisioning).
   */
  void execRawExitCodeFor(final String machineName, final int code) {
    execRawExitCodeByMachine.put(machineName, code);
  }

  void failFor(final String machineName, final RuntimeException failure) {
    failureByMachine.put(machineName, failure);
  }

  void hostKeyMismatchFor(final String machineName) {
    hostKeyMismatchMachines.add(machineName);
  }

  List<ExecCall> execCalls() {
    return List.copyOf(execCalls);
  }

  List<ExecRawCall> execRawCalls() {
    return List.copyOf(execRawCalls);
  }

  List<PutFileCall> putFileCalls() {
    return List.copyOf(putFileCalls);
  }

  List<String> pinHostKeyCalls() {
    return List.copyOf(pinHostKeyCalls);
  }

  @Override
  public int exec(
      final ResolvedSshTarget target, final List<String> remoteCommand, final PrintStream out) {
    execCalls.add(new ExecCall(target.machineName(), remoteCommand));
    final RuntimeException failure = failureByMachine.get(target.machineName());
    if (failure != null) {
      throw failure;
    }
    return exitCodeByMachine.getOrDefault(target.machineName(), 0);
  }

  @Override
  public int execRaw(
      final ResolvedSshTarget target, final List<String> remoteCommand, final PrintStream out) {
    execRawCalls.add(new ExecRawCall(target.machineName(), remoteCommand));
    return execRawExitCodeByMachine.getOrDefault(target.machineName(), 0);
  }

  @Override
  public void putFile(
      final ResolvedSshTarget target, final Path localFile, final String remotePath) {
    putFileCalls.add(new PutFileCall(target.machineName(), localFile, remotePath));
  }

  @Override
  public void pinHostKey(final ResolvedSshTarget target, final Path knownHostsFile) {
    pinHostKeyCalls.add(target.machineName());
    if (hostKeyMismatchMachines.contains(target.machineName())) {
      throw new HilmirException("simulated SSH host key mismatch for " + target.machineName());
    }
  }
}
