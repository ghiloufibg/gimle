package com.gimle.agent;

import com.gimle.core.exception.GimleClusterException;
import com.gimle.module.lifecycle.ModuleContext;
import com.gimle.module.lifecycle.ModuleLifecycleHooks;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Repeatedly looks up and calls a real {@link FabricGreeter} across a real cross-machine fabric
 * hop, appending one line per attempt to a file named by the {@code gimle.test.outputFile} system
 * property -- the only way {@link FabricCrossProcessIntegrationTest} (running in a separate process
 * from this hook) can observe what happened inside a real worker subprocess.
 *
 * <p>The retry/call loop runs on its own background thread, not inside {@code onStart} itself:
 * {@code onStart} runs synchronously on the worker's single control-channel receive loop (the
 * gating-hook semantics {@code ModuleController} documents), and that is the exact same loop that
 * must process the incoming {@code ControlMessage.CatalogUpdate} this hook is waiting for -- a hook
 * that blocked in {@code onStart} itself would deadlock against its own worker, never seeing the
 * catalog update that would satisfy the very lookup it's retrying.
 */
public class FabricGreeterConsumerHooks implements ModuleLifecycleHooks {

  private static final Duration LOOKUP_RETRY_WINDOW = Duration.ofSeconds(60);
  private static final Duration CALL_LOOP_WINDOW = Duration.ofSeconds(45);
  private static final Duration CALL_INTERVAL = Duration.ofSeconds(1);

  @Override
  public void onInstall(ModuleContext ctx) {}

  @Override
  public void onStart(ModuleContext ctx) {
    Thread.ofVirtual().name("fabric-greeter-consumer-loop").start(() -> runLoop(ctx));
  }

  private static void runLoop(ModuleContext ctx) {
    Path outputFile = Path.of(System.getProperty("gimle.test.outputFile"));
    Instant deadline = Instant.now().plus(LOOKUP_RETRY_WINDOW);
    Optional<FabricGreeter> greeter = Optional.empty();
    while (Instant.now().isBefore(deadline) && greeter.isEmpty()) {
      try {
        greeter = ctx.lookupService(FabricGreeter.class);
      } catch (GimleClusterException e) {
        greeter = Optional.empty(); // no known exporter yet; keep retrying
      }
      if (greeter.isEmpty()) {
        sleep(Duration.ofMillis(300));
      }
    }
    if (greeter.isEmpty()) {
      appendLine(outputFile, "LOOKUP_TIMEOUT");
      return;
    }

    Instant callDeadline = Instant.now().plus(CALL_LOOP_WINDOW);
    while (Instant.now().isBefore(callDeadline)) {
      try {
        appendLine(outputFile, "OK:" + greeter.get().greet("world"));
      } catch (RuntimeException e) {
        appendLine(outputFile, "FAIL:" + e.getClass().getSimpleName());
      }
      sleep(CALL_INTERVAL);
    }
  }

  @Override
  public void onStop(ModuleContext ctx) {}

  @Override
  public void onUninstall(ModuleContext ctx) {}

  private static void sleep(Duration duration) {
    try {
      Thread.sleep(duration);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private static void appendLine(Path file, String line) {
    try {
      OpenOption[] options = {
        StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.WRITE
      };
      Files.writeString(file, line + "\n", options);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
