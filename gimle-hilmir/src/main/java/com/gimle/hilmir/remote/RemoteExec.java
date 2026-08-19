package com.gimle.hilmir.remote;

import java.io.PrintStream;
import java.nio.file.Path;
import java.util.List;

/**
 * The transport seam {@link RemoteDispatch} drives: run a command on a resolved target machine, or
 * copy a file to it. {@link SshProcessExec} is the real, production implementation (shells out to
 * the operator's own {@code ssh}/{@code scp}); tests substitute a fake, the same seam shape {@code
 * com.gimle.hilmir.launch.MachineLauncher}'s own package-visible {@code up(ClusterPlan, ...)}
 * overload already establishes for local process spawning.
 */
public interface RemoteExec {

  /**
   * Runs {@code remoteCommand} on {@code target}, with {@code target.installDir()} as the working
   * directory (so a data root the remote {@code hilmir} resolves relative to its own CWD lands in
   * the same place on {@code up} as it does on a later {@code down}/{@code status}, regardless of
   * the SSH session's own default directory). Streams the remote command's output to {@code out},
   * prefixed with the target's machine name. Returns the remote command's exit code -- never throws
   * for a non-zero exit, only for a transport failure (connection refused, timeout, ...).
   */
  int exec(ResolvedSshTarget target, List<String> remoteCommand, PrintStream out);

  /** Copies {@code localFile} to {@code remotePath} on {@code target}. Throws on any failure. */
  void putFile(ResolvedSshTarget target, Path localFile, String remotePath);
}
