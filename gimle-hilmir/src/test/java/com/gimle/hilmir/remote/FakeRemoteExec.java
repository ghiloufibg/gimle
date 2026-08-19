package com.gimle.hilmir.remote;

import java.io.PrintStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A scriptable, thread-safe {@link RemoteExec} fake: records every {@code exec}/{@code putFile}
 * call it receives (concurrently, since {@link RemoteDispatch} fans out over one virtual thread per
 * machine) and lets a test script a per-machine exit code or thrown failure -- mirrors {@code
 * com.gimle.hilmir.launch.LaunchTestSupport}'s role for {@code MachineLauncher}'s own tests, but
 * for the SSH transport seam instead of a real spawned process.
 */
final class FakeRemoteExec implements RemoteExec {

  record ExecCall(String machineName, List<String> command) {}

  record PutFileCall(String machineName, Path localFile, String remotePath) {}

  private final Map<String, Integer> exitCodeByMachine = new ConcurrentHashMap<>();
  private final Map<String, RuntimeException> failureByMachine = new ConcurrentHashMap<>();
  private final List<ExecCall> execCalls = new CopyOnWriteArrayList<>();
  private final List<PutFileCall> putFileCalls = new CopyOnWriteArrayList<>();

  void exitCodeFor(final String machineName, final int code) {
    exitCodeByMachine.put(machineName, code);
  }

  void failFor(final String machineName, final RuntimeException failure) {
    failureByMachine.put(machineName, failure);
  }

  List<ExecCall> execCalls() {
    return List.copyOf(execCalls);
  }

  List<PutFileCall> putFileCalls() {
    return List.copyOf(putFileCalls);
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
  public void putFile(
      final ResolvedSshTarget target, final Path localFile, final String remotePath) {
    putFileCalls.add(new PutFileCall(target.machineName(), localFile, remotePath));
  }
}
