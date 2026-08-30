package com.gimle.worker;

import com.gimle.core.protocol.ControlMessage;
import com.gimle.module.lifecycle.ModuleContext;
import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Turns a hosted module's synchronous {@link ModuleContext#relayControlPlaneRead} call into a real
 * request/response round trip over the worker-agent control channel -- {@code WorkerMain.main()}'s
 * own receive loop is the only reader of that channel, so an arbitrary calling thread (a virtual
 * thread handling some real inbound request, running module hook code) can't call {@code
 * ControlChannelClient.receive()} itself. Instead, {@link #request} registers a {@link
 * CompletableFuture} keyed by a fresh correlation id, sends the request, and blocks on that future;
 * {@link #complete}, called from the receive loop when a matching {@code RelayControlPlaneResult}
 * arrives, is what unblocks it.
 */
final class ControlPlaneRelay {

  private static final Logger log = LoggerFactory.getLogger(ControlPlaneRelay.class);

  /**
   * A few seconds: generous enough that an ordinary round trip (worker to agent, agent's own real
   * HTTP call to the control plane, and back) comfortably finishes even under load, short enough
   * that a module thread blocked here -- typically itself handling some real inbound request --
   * doesn't hang indefinitely if the agent or control plane never answers at all.
   */
  private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(5);

  private final ControlChannelClient channel;
  private final Duration timeout;
  private final Map<String, CompletableFuture<ControlMessage.RelayControlPlaneResult>> pending =
      new ConcurrentHashMap<>();

  ControlPlaneRelay(ControlChannelClient channel) {
    this(channel, DEFAULT_TIMEOUT);
  }

  ControlPlaneRelay(ControlChannelClient channel, Duration timeout) {
    this.channel = channel;
    this.timeout = timeout;
  }

  /**
   * Sends a {@code RelayControlPlaneRead} for {@code path} and blocks the calling thread until
   * either a matching result arrives or {@link #timeout} elapses -- never throws for an ordinary
   * relay failure, matching {@link ModuleContext}'s own "modules see values, not exceptions"
   * posture: every failure mode (send failed, timed out, interrupted) comes back as a synthesized
   * {@link ModuleContext.RelayResult} instead. The pending entry is always removed, on every exit
   * path, so a timed-out call never leaks a map entry.
   */
  ModuleContext.RelayResult request(String path) {
    return exchange(
        "read " + path,
        correlationId -> new ControlMessage.RelayControlPlaneRead(correlationId, path));
  }

  /**
   * The status-write sibling of {@link #request}: sends a {@code RelayResourceStatusPut} and blocks
   * for the same {@code RelayControlPlaneResult} shape a read gets, over the identical
   * pending-future machinery -- the agent answers both message kinds with one result type, matched
   * by correlation id.
   */
  ModuleContext.RelayResult requestStatusPut(
      String kindName, java.util.Optional<String> tenantId, String name, String statusJson) {
    return exchange(
        "status put for " + kindName + "/" + name,
        correlationId ->
            new ControlMessage.RelayResourceStatusPut(
                correlationId, kindName, tenantId.orElse(""), name, statusJson));
  }

  private ModuleContext.RelayResult exchange(
      String what, java.util.function.Function<String, ControlMessage> message) {
    String correlationId = UUID.randomUUID().toString();
    CompletableFuture<ControlMessage.RelayControlPlaneResult> future = new CompletableFuture<>();
    pending.put(correlationId, future);
    try {
      channel.send(message.apply(correlationId));
      ControlMessage.RelayControlPlaneResult result =
          future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
      return new ModuleContext.RelayResult(result.status(), result.body());
    } catch (IOException e) {
      log.warn("failed to send control-plane relay request for {}: {}", what, e.getMessage());
      return new ModuleContext.RelayResult(
          502, "worker could not send the relay request: " + e.getMessage());
    } catch (TimeoutException e) {
      return new ModuleContext.RelayResult(504, "control-plane relay request timed out");
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return new ModuleContext.RelayResult(
          504, "interrupted while awaiting the control-plane relay response");
    } catch (ExecutionException e) {
      // CompletableFuture#complete (see #complete below) never completes this future
      // exceptionally, so this branch is unreachable in practice -- guarded rather than left to
      // propagate as an unchecked wrapper a module hook has no way to anticipate.
      log.warn("unexpected failure completing control-plane relay future: {}", e.getMessage());
      return new ModuleContext.RelayResult(500, "unexpected relay failure: " + e.getMessage());
    } finally {
      pending.remove(correlationId);
    }
  }

  /**
   * Called from {@code WorkerMain}'s receive loop -- the only reader of this worker's control
   * channel -- when a {@code RelayControlPlaneResult} arrives. No matching future (the caller
   * already timed out and moved on, or this result is a stray from a connection this worker no
   * longer recognizes) is logged and dropped, the same "state moved on" tolerance this codebase
   * already shows for a late {@code Ack}/{@code Nack}.
   */
  void complete(ControlMessage.RelayControlPlaneResult result) {
    CompletableFuture<ControlMessage.RelayControlPlaneResult> future =
        pending.remove(result.correlationId());
    if (future == null) {
      log.debug(
          "no pending caller for control-plane relay result {}; dropping", result.correlationId());
      return;
    }
    future.complete(result);
  }

  /** How many callers are currently blocked in {@link #request} -- test-only visibility. */
  int pendingRequestCount() {
    return pending.size();
  }
}
