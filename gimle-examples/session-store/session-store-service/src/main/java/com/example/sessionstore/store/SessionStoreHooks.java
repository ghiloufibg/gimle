package com.example.sessionstore.store;

import com.example.sessionstore.SessionStore;
import com.gimle.module.lifecycle.ModuleContext;
import com.gimle.module.lifecycle.ModuleLifecycleHooks;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The store itself: an append-only log under {@link ModuleContext#dataDirectory()} is the durable
 * source of truth, replayed into an in-memory {@link ConcurrentHashMap} index once on {@link
 * #onStart} -- every {@link #get} answers straight from that index (fast, no disk read per call),
 * while every {@link #put}/{@link #delete} appends to the log before updating the index, so a
 * crash between the two never leaves the index ahead of what's durably recorded.
 *
 * <p>This is the actual point of the whole app: {@code gimle-module.yaml}'s {@code volume:}
 * declaration is what makes {@link ModuleContext#dataDirectory()} present at all (absent
 * otherwise, see that method's own javadoc), and the log this class writes there is a real one --
 * kill this instance (or redeploy the module) and a fresh instance mounted on the same volume
 * replays the exact same log and answers the exact same {@link #get} calls a client asked before
 * the restart. See ../README.md for how to actually exercise that restart by hand.
 *
 * <p>Log format: one line per mutation, tab-separated, {@code PUT\t<sessionId>\t<base64(value)>}
 * or {@code DEL\t<sessionId>}. Base64-encoding the value sidesteps any tab/newline the value
 * itself might contain -- {@code sessionId} is assumed not to contain a tab, the same assumption
 * every line-oriented log format makes about its own key space.
 */
public final class SessionStoreHooks implements ModuleLifecycleHooks, SessionStore {

  private static final Logger log = LoggerFactory.getLogger(SessionStoreHooks.class);
  private static final String LOG_FILE_NAME = "sessions.log";
  private static final String PUT_MARKER = "PUT";
  private static final String DELETE_MARKER = "DEL";

  static final AtomicBoolean ready = new AtomicBoolean(false);

  private final Map<String, String> index = new ConcurrentHashMap<>();
  private final Object writeLock = new Object();
  private Path logFile;

  @Override
  public void onInstall(ModuleContext ctx) {}

  @Override
  public void onStart(ModuleContext ctx) {
    Optional<Path> dataDirectory = ctx.dataDirectory();
    if (dataDirectory.isEmpty()) {
      // Every other example module in this repo tolerates a missing optional; this one can't --
      // without a real volume there is nowhere durable to write, so failing loudly here (rather
      // than silently degrading to in-memory-only) is the honest behavior for a module whose
      // entire purpose is durability.
      throw new IllegalStateException(
          "session-store-service requires a volume: no dataDirectory was allocated -- check this"
              + " module's own gimle-module.yaml declares volume: and this instance was deployed"
              + " as a StatefulSet");
    }
    logFile = dataDirectory.get().resolve(LOG_FILE_NAME);
    int recovered = replayLog();
    ctx.registerService(SessionStore.class, this);
    ready.set(true);
    if (recovered > 0) {
      log.info(
          "session-store-service recovered {} session(s) from an existing volume at {}",
          recovered,
          logFile);
    } else {
      log.info("session-store-service starting with an empty volume at {}", logFile);
    }
  }

  @Override
  public void onStop(ModuleContext ctx) {
    ready.set(false);
  }

  @Override
  public void onUninstall(ModuleContext ctx) {}

  @Override
  public void put(String sessionId, String value) {
    synchronized (writeLock) {
      appendLine(PUT_MARKER + '\t' + sessionId + '\t' + encode(value));
      index.put(sessionId, value);
    }
  }

  @Override
  public String get(String sessionId) {
    return index.get(sessionId);
  }

  @Override
  public void delete(String sessionId) {
    synchronized (writeLock) {
      appendLine(DELETE_MARKER + '\t' + sessionId);
      index.remove(sessionId);
    }
  }

  /** Replays every log line into {@link #index}; returns the number of sessions recovered. */
  private int replayLog() {
    if (!Files.exists(logFile)) {
      return 0;
    }
    try {
      for (String line : Files.readAllLines(logFile, StandardCharsets.UTF_8)) {
        if (line.isBlank()) {
          continue;
        }
        String[] parts = line.split("\t", 3);
        switch (parts[0]) {
          case PUT_MARKER -> index.put(parts[1], decode(parts[2]));
          case DELETE_MARKER -> index.remove(parts[1]);
          default -> log.warn("skipping unrecognized log line while replaying: {}", line);
        }
      }
    } catch (IOException e) {
      throw new UncheckedIOException("failed to replay session log at " + logFile, e);
    }
    return index.size();
  }

  private void appendLine(String line) {
    try {
      Files.writeString(
          logFile,
          line + System.lineSeparator(),
          StandardCharsets.UTF_8,
          StandardOpenOption.CREATE,
          StandardOpenOption.APPEND);
    } catch (IOException e) {
      throw new UncheckedIOException("failed to append to session log at " + logFile, e);
    }
  }

  private static String encode(String value) {
    return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
  }

  private static String decode(String encoded) {
    return new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8);
  }
}
