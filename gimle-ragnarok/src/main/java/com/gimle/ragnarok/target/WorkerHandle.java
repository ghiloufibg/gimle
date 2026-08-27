package com.gimle.ragnarok.target;

/**
 * The live worker process currently hosting one deployment instance -- deliberately minimal rather
 * than reusing the full {@link GimleProcess} contract: {@code Fenrir}'s own worker-kill fault only
 * ever needs {@link #pid()}, {@link #isAlive()}, and {@link #kill()} (worker respawn is the
 * platform's own supervisor's job, never the chaos tool's), so a {@code restart()}/{@code onExit()}
 * a remote implementation would have no honest way to support stays off this interface entirely
 * rather than being faked. A local, in-JVM target can trivially wrap a real {@link ProcessHandle};
 * an SSH-backed target resolves a remote PID some other way entirely (no {@code ProcessHandle} of
 * any kind exists for a process on a machine this JVM isn't running on).
 */
public interface WorkerHandle {

  long pid();

  boolean isAlive();

  void kill();
}
