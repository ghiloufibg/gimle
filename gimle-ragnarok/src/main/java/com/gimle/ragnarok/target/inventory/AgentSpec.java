package com.gimle.ragnarok.target.inventory;

import com.gimle.ragnarok.RagnarokException;
import java.nio.file.Path;

/**
 * One node agent, for resolving a worker instance's real OS pid: which machine it runs on, its
 * Gimlé node id (what a deployment's {@code placements()} names it by), and its own {@code
 * -Dgimle.log.root} directory -- the same convention {@code gimle-hilmir}'s own {@code
 * LaunchPlanner} sets, containing {@code agent-platform.log}, the one place a worker's OS pid is
 * ever recorded (via {@code WorkerProcessSupervisor}'s own {@code "spawned worker ... as pid ..."}
 * line).
 */
public record AgentSpec(String machine, String nodeId, Path logRoot) {

  public AgentSpec {
    if (machine == null || machine.isBlank()) {
      throw new RagnarokException("an agent must name a non-blank machine");
    }
    if (nodeId == null || nodeId.isBlank()) {
      throw new RagnarokException("an agent must have a non-blank nodeId");
    }
    if (logRoot == null) {
      throw new RagnarokException("agent " + nodeId + " must declare a logRoot");
    }
  }
}
