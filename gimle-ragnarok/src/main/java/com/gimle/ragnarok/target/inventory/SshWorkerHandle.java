package com.gimle.ragnarok.target.inventory;

import com.gimle.hilmir.HilmirException;
import com.gimle.hilmir.remote.RemoteExec;
import com.gimle.hilmir.remote.ResolvedSshTarget;
import com.gimle.ragnarok.RagnarokException;
import com.gimle.ragnarok.target.WorkerHandle;
import java.io.OutputStream;
import java.io.PrintStream;
import java.util.List;

/**
 * A worker instance's real OS pid on a remote machine, controlled over SSH via {@link RemoteExec}
 * -- deliberately simpler than {@link SshManagedProcess}: a worker-kill victim is resolved fresh
 * for each strike (see {@code SshInventoryClusterTarget#workerFor}), so there is no restart to
 * track and no pid file to re-read, just the one pid resolved at construction.
 */
final class SshWorkerHandle implements WorkerHandle {

  private final RemoteExec remoteExec;
  private final ResolvedSshTarget target;
  private final long pid;

  SshWorkerHandle(final RemoteExec remoteExec, final ResolvedSshTarget target, final long pid) {
    this.remoteExec = remoteExec;
    this.target = target;
    this.pid = pid;
  }

  @Override
  public long pid() {
    return pid;
  }

  @Override
  public boolean isAlive() {
    return runScript("kill -0 " + pid + " 2>/dev/null") == 0;
  }

  @Override
  public void kill() {
    runScript("kill -9 " + pid + " 2>/dev/null; true");
  }

  private int runScript(final String script) {
    final PrintStream discard = new PrintStream(OutputStream.nullOutputStream());
    try {
      return remoteExec.execRaw(target, List.of("sh", "-c", script), discard);
    } catch (final HilmirException e) {
      throw new RagnarokException("SSH command failed against " + target.machineName(), e);
    }
  }
}
