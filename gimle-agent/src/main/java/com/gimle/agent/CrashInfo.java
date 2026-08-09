package com.gimle.agent;

import java.nio.file.Path;
import java.util.Optional;

/**
 * Classification of one unexpected worker exit, produced by {@link WorkerProcessSupervisor#onExit}
 * and handed to its {@code onCrash} callback. {@code hsErrLog}, when present, is the path a fresh
 * {@code hs_err_pid<pid>.log} dump was found at -- already listable via {@code AgentLogServer}'s
 * own crash-dump route, now correlated with the specific exit that produced it rather than left for
 * an operator to notice separately.
 */
public record CrashInfo(Cause cause, int exitCode, Optional<Path> hsErrLog) {

  /**
   * {@code OOM} relies on {@code -XX:+ExitOnOutOfMemoryError} (set unconditionally in {@link
   * AgentMain#buildWorkerCommand}) making HotSpot exit with code {@value
   * WorkerProcessSupervisor#OOM_EXIT_CODE} specifically for this cause -- unambiguous and portable,
   * unlike scraping a log line. {@code NATIVE_CRASH} is anything else where a fresh {@code
   * hs_err_pid<pid>.log} exists (a JVM crash dump is only ever written for a genuine native-level
   * fault). {@code UNKNOWN} is every other exit code with no crash dump -- most commonly {@code
   * destroyForcibly()} racing a real crash, or an exit this platform hasn't seen before.
   */
  public enum Cause {
    OOM,
    NATIVE_CRASH,
    UNKNOWN
  }
}
