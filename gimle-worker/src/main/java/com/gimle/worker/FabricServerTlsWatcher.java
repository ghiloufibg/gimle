package com.gimle.worker;

import com.gimle.core.tls.TlsSettings;
import com.gimle.core.tls.TransportProtocol;
import com.gimle.fabric.transport.FabricServer;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Detects that this worker's agent-managed certificate file changed on disk -- overwritten in place
 * by the agent's own rotation, per {@code claudedocs/tls-transport-security-design.md} §6.2 -- by
 * polling {@link Files#getLastModifiedTime} against the value last seen, and calls {@link
 * FabricServer#reloadTlsMaterial()} when it moves. A worker carries no {@code gimle-pki} dependency
 * and has no channel back to the agent to be told "your cert changed" (§3, §6.2); polling is
 * deliberate here, not a placeholder for a push mechanism that doesn't exist. No-op -- never even
 * starts its ticker -- in plaintext mode.
 */
public final class FabricServerTlsWatcher implements AutoCloseable {

  private static final Logger log = LoggerFactory.getLogger(FabricServerTlsWatcher.class);

  private final ScheduledExecutorService ticker =
      Executors.newSingleThreadScheduledExecutor(
          r -> Thread.ofVirtual().name("gimle-fabric-tls-watcher").unstarted(r));
  private volatile FileTime lastSeen;

  public void start(FabricServer server, Duration pollInterval) {
    if (TransportProtocol.fromConfig() == TransportProtocol.PLAINTEXT) {
      return;
    }
    lastSeen = readModifiedTimeQuietly();
    ticker.scheduleAtFixedRate(
        () -> tick(server),
        pollInterval.toMillis(),
        pollInterval.toMillis(),
        TimeUnit.MILLISECONDS);
  }

  private void tick(FabricServer server) {
    FileTime current = readModifiedTimeQuietly();
    if (current == null || current.equals(lastSeen)) {
      return;
    }
    lastSeen = current;
    server.reloadTlsMaterial();
    log.info("fabric server TLS material reloaded after detecting a certificate file change");
  }

  private static FileTime readModifiedTimeQuietly() {
    try {
      return Files.getLastModifiedTime(TlsSettings.fromConfig().certFile());
    } catch (IOException | RuntimeException e) {
      log.warn("failed to poll certificate file mtime: {}", e.getMessage());
      return null;
    }
  }

  @Override
  public void close() {
    ticker.shutdownNow();
  }
}
