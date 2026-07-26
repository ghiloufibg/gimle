package com.gimle.worker;

import com.gimle.core.module.ModuleArtifact;
import com.gimle.core.module.ModuleId;
import com.gimle.core.protocol.ControlMessage;
import com.gimle.module.artifact.ModuleArtifactReader;
import com.gimle.module.layer.PlatformLayer;
import com.gimle.module.lifecycle.LifecycleEvent;
import com.gimle.module.lifecycle.ModuleController;
import com.gimle.module.lifecycle.SimpleServiceRegistry;
import com.gimle.module.resolve.ModuleRegistry;
import com.gimle.module.resolve.ModuleResolver;
import java.io.IOException;
import java.net.UnixDomainSocketAddress;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The worker JVM's entry point (design §3.1): connects out to the agent's control socket, then
 * treats every module operation -- including the very first module this worker ever hosts -- as
 * arriving over that channel. There's deliberately no separate "initial load" path: a
 * freshly-started worker and one mid-redeploy look identical from here.
 */
public final class WorkerMain {

  private static final Logger log = LoggerFactory.getLogger(WorkerMain.class);

  private WorkerMain() {}

  public static void main(String[] args) throws IOException {
    if (args.length != 1) {
      System.err.println("usage: WorkerMain <control-socket-path>");
      System.exit(2);
      return;
    }

    UnixDomainSocketAddress address = UnixDomainSocketAddress.of(Path.of(args[0]));
    ControlChannelClient channel =
        ControlChannelClient.connectWithRetry(
            address, Duration.ofMillis(200), Duration.ofSeconds(30));
    log.info("connected to agent control socket at {}", address);

    ModuleRegistry registry = new ModuleRegistry();
    ModuleResolver resolver = new ModuleResolver(registry);
    ModuleLayer platform = PlatformLayer.bootOnly().layer();

    AtomicReference<WorkerRuntime> runtimeRef = new AtomicReference<>();
    Consumer<LifecycleEvent> sink =
        event -> {
          runtimeRef.get().onLifecycleEvent(event);
          sendQuietly(channel, new ControlMessage.ModuleStateChanged(event.id(), stateName(event)));
        };
    ModuleController controller =
        new ModuleController(
            registry,
            resolver,
            platform,
            ClassLoader.getSystemClassLoader(),
            Duration.ofSeconds(5),
            sink);
    WorkerRuntime runtime =
        new WorkerRuntime(
            controller,
            registry,
            new SimpleServiceRegistry(),
            4,
            Duration.ofSeconds(1),
            Duration.ofSeconds(2),
            3,
            id -> log.error("module {} exhausted its restart budget; awaiting worker restart", id));
    runtimeRef.set(runtime);

    long pid = ProcessHandle.current().pid();
    channel.send(new ControlMessage.Hello("worker-" + pid, pid));

    Optional<ControlMessage> received;
    while ((received = channel.receive()).isPresent()) {
      handle(received.get(), registry, controller, channel);
    }
    log.info("control channel closed by agent; shutting down");
  }

  private static void handle(
      ControlMessage message,
      ModuleRegistry registry,
      ModuleController controller,
      ControlChannelClient channel)
      throws IOException {
    switch (message) {
      case ControlMessage.InstallModule m -> {
        try {
          ModuleArtifact artifact = ModuleArtifactReader.read(Path.of(m.artifactPath()));
          ModuleId id = registry.register(artifact);
          channel.send(new ControlMessage.ModuleStateChanged(id, "INSTALLED"));
          channel.send(new ControlMessage.Ack(m.correlationId()));
        } catch (RuntimeException e) {
          channel.send(new ControlMessage.Nack(m.correlationId(), String.valueOf(e.getMessage())));
        }
      }
      case ControlMessage.ResolveModule m ->
          runCommand(m.correlationId(), channel, () -> controller.resolve(m.id()));
      case ControlMessage.StartModule m ->
          runCommand(m.correlationId(), channel, () -> controller.start(m.id()));
      case ControlMessage.StopModule m ->
          runCommand(m.correlationId(), channel, () -> controller.stop(m.id()));
      case ControlMessage.UninstallModule m ->
          runCommand(m.correlationId(), channel, () -> controller.uninstall(m.id()));
      case ControlMessage.Ping m -> channel.send(new ControlMessage.Pong(m.correlationId()));
      default -> log.warn("unexpected control message from agent: {}", message);
    }
  }

  private static void runCommand(
      String correlationId, ControlChannelClient channel, Runnable action) throws IOException {
    try {
      action.run();
      channel.send(new ControlMessage.Ack(correlationId));
    } catch (RuntimeException e) {
      channel.send(new ControlMessage.Nack(correlationId, String.valueOf(e.getMessage())));
    }
  }

  private static String stateName(LifecycleEvent event) {
    return switch (event) {
      case LifecycleEvent.Installed ignored -> "INSTALLED";
      case LifecycleEvent.Resolved ignored -> "RESOLVED";
      case LifecycleEvent.Starting ignored -> "STARTING";
      case LifecycleEvent.Active ignored -> "ACTIVE";
      case LifecycleEvent.Stopping ignored -> "STOPPING";
      case LifecycleEvent.Uninstalled ignored -> "UNINSTALLED";
      case LifecycleEvent.TransitionFailed ignored -> "FAILED";
    };
  }

  private static void sendQuietly(ControlChannelClient channel, ControlMessage message) {
    try {
      channel.send(message);
    } catch (IOException e) {
      log.warn("failed to send {} over control channel: {}", message, e.getMessage());
    }
  }
}
