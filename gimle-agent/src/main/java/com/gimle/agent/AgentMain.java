package com.gimle.agent;

import com.gimle.core.exception.GimleIsolationException;
import com.gimle.core.module.IsolationTier;
import com.gimle.core.module.ModuleId;
import com.gimle.core.module.ResourceSpec;
import com.gimle.core.module.Version;
import com.gimle.core.protocol.ControlMessage;
import com.gimle.core.restart.RestartTracker;
import com.gimle.os.ResourceLimitHandle;
import com.gimle.os.ResourceLimiter;
import com.gimle.os.portable.PortableJvmFlagsResourceLimiter;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The node agent's entry point (design §4). Phase 2 has no control plane to receive a placement
 * from, so this spawns and supervises exactly the one worker its CLI arguments describe -- proving
 * the spawn/supervise/control-channel wiring end to end without inventing a scheduling API that
 * only Phase 3's API-server integration would actually call.
 *
 * <p>Usage: {@code AgentMain <workerId> <isolationTier> <memory> <cpu> <controlSocketPath>
 * <javaExecutable> <worker-command-tail...>} -- the worker command tail is everything after the
 * java executable that launches {@code gimle-worker}'s {@code WorkerMain} (module-path/classpath
 * and main class), in the exact order the caller wants; the derived {@code -Xmx}/{@code
 * -XX:ActiveProcessorCount} flags are inserted immediately after the executable, and the
 * control-socket path is appended as the worker's sole application argument by {@link
 * WorkerProcessSupervisor} itself.
 */
public final class AgentMain {

  private static final Logger log = LoggerFactory.getLogger(AgentMain.class);

  private AgentMain() {}

  public static void main(String[] args) throws IOException {
    if (args.length < 6) {
      System.err.println(
          "usage: AgentMain <workerId> <isolationTier> <memory> <cpu> <controlSocketPath>"
              + " <javaExecutable> <worker-command-tail...>");
      System.exit(2);
      return;
    }

    String workerId = args[0];
    IsolationTier tier = IsolationTier.valueOf(args[1]);
    ResourceSpec limit = new ResourceSpec(args[2], args[3]);
    Path controlSocketPath = Path.of(args[4]);
    String javaExecutable = args[5];
    List<String> commandTail = List.of(args).subList(6, args.length);

    ResourceLimiter resourceLimiter = new PortableJvmFlagsResourceLimiter();
    if (!resourceLimiter.supports(tier)) {
      throw GimleIsolationException.tier_unsupported(
          new ModuleId(workerId, Version.parse("0.0.0")), tier);
    }

    CapacityTracker capacityTracker = CapacityTracker.of_this_machine();
    if (!capacityTracker.try_assign(workerId, limit)) {
      log.error("worker {} does not fit this machine's remaining capacity", workerId);
      System.exit(1);
      return;
    }

    ResourceLimitHandle handle = resourceLimiter.prepare(workerId, limit);
    List<String> baseCommand = new ArrayList<>();
    baseCommand.add(javaExecutable);
    baseCommand.addAll(resourceLimiter.jvm_flags(handle));
    baseCommand.addAll(commandTail);

    try (ControlChannelServer server = new ControlChannelServer(controlSocketPath)) {
      RestartTracker restartTracker =
          new RestartTracker(
              Duration.ofSeconds(1), 2.0, Duration.ofSeconds(30), 5, Duration.ofMinutes(10));
      WorkerProcessSupervisor supervisor =
          new WorkerProcessSupervisor(
              workerId,
              baseCommand,
              controlSocketPath,
              restartTracker,
              exhaustedId -> {
                log.error(
                    "worker {} is permanently down; its budget is exhausted with no control plane"
                        + " to reschedule to",
                    exhaustedId);
                resourceLimiter.release(handle);
                capacityTracker.release(exhaustedId);
              });
      supervisor.start();

      try (WorkerConnection connection = server.accept()) {
        log.info("worker {} connected", workerId);
        Optional<ControlMessage> received;
        while ((received = connection.receive()).isPresent()) {
          ControlMessage message = received.get();
          switch (message) {
            case ControlMessage.Ping ping ->
                connection.send(new ControlMessage.Pong(ping.correlationId()));
            default -> log.info("worker {} reported: {}", workerId, message);
          }
        }
        log.info("worker {} disconnected", workerId);
      } finally {
        supervisor.close();
        resourceLimiter.release(handle);
        capacityTracker.release(workerId);
      }
    }
  }
}
