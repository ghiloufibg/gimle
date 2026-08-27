package com.gimle.ragnarok.target;

import com.gimle.testkit.heimdall.HeimdallProcess;
import java.nio.file.Path;

/**
 * A handle on one cluster process a {@link ClusterTarget} can control directly. {@code endpoint()}
 * is the address peers and clients use to reach it -- the client port for a store, the HTTP port
 * for everything else, and the gossip bind address for an agent.
 */
public interface GimleProcess {

  /** A short label naming this process's kind -- {@code "STORE"}, {@code "CONTROLPLANE"}... */
  String role();

  /** Stable identity within the cluster: {@code store-0}, {@code controlplane-1}, a node id... */
  String id();

  long pid();

  boolean isAlive();

  void kill();

  /** Destroys the process's descendants first (a worker under an agent), then the process. */
  void killWithDescendants();

  /** Respawns with the identical command line, appending to the same log file. */
  void restart();

  /**
   * Registers a callback invoked whenever the underlying process exits -- including a respawned one
   * after {@link #restart()}. Fires on harness-initiated kills too; distinguish with {@link
   * #exitWasExpected()}.
   */
  void onExit(Runnable callback);

  /** True when the most recent exit (or in-flight kill) was initiated by the harness itself. */
  boolean exitWasExpected();

  Path logFile();

  String endpoint();

  /** Adapts this to the minimal shape {@code gimle-testkit}'s Heimdall watcher needs. */
  default HeimdallProcess asHeimdallProcess() {
    return new HeimdallProcess() {
      @Override
      public String role() {
        return GimleProcess.this.role();
      }

      @Override
      public String id() {
        return GimleProcess.this.id();
      }

      @Override
      public boolean isAlive() {
        return GimleProcess.this.isAlive();
      }

      @Override
      public boolean exitWasExpected() {
        return GimleProcess.this.exitWasExpected();
      }

      @Override
      public void onExit(final Runnable callback) {
        GimleProcess.this.onExit(callback);
      }
    };
  }
}
