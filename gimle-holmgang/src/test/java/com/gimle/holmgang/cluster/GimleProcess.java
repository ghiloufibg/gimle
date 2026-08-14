package com.gimle.holmgang.cluster;

import com.gimle.holmgang.topology.ProcessRole;
import java.nio.file.Path;

/**
 * A handle on one spawned cluster process. {@code endpoint()} is the address peers and clients use
 * to reach it -- the client port for a store, the HTTP port for everything else, and the gossip
 * bind address for an agent.
 */
public interface GimleProcess {

  ProcessRole role();

  /** Stable identity within the cluster: {@code store-0}, {@code controlplane-1}, a node id... */
  String id();

  long pid();

  boolean isAlive();

  void kill();

  /** Destroys the process's descendants first (a worker under an agent), then the process. */
  void killWithDescendants();

  /** Respawns with the identical command line, appending to the same log file. */
  void restart();

  Path logFile();

  String endpoint();
}
