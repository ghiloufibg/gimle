package com.gimle.module.leak;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import jdk.jfr.FlightRecorder;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedClass;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedObject;
import jdk.jfr.consumer.RecordingFile;

/**
 * Best-effort retaining-path attribution via JFR's {@code jdk.OldObjectSample} event. Getting the
 * actual reference-chain-to-GC-root data (not just object identity/type/age) requires {@code
 * path-to-gc-roots=true}, which is a recording-launch option (jcmd / {@code
 * -XX:StartFlightRecording}), not something settable through the public {@code Recording} API in
 * process -- confirmed empirically (JDK 25): a second, independently-created {@code Recording}
 * inside an already-{@code path-to-gc-roots}-enabled JVM gets no chain data at all, only the
 * specific recording actually launched with that flag does. Worse, that chain data is only
 * populated when <em>that exact recording</em> is dumped (its {@code jdk.OldObjectSample} events
 * carry a null {@code referrer} otherwise, even mid-recording) -- so this correlator locates the
 * worker's own launch-time recording by name (see {@code AgentMain}'s {@code
 * LEAK_DETECTION_JFR_FLAG}) and dumps <em>it</em> on demand, rather than streaming events live via
 * {@code RecordingStream}, which was the original (unworkable) approach this class started with.
 *
 * <p>Without that launch flag -- the correlator running outside a properly-launched worker, e.g.
 * under a unit test -- no matching recording is found and this degrades to always reporting no
 * path, the same degraded-but-honest behavior as ever: a match still confirms <em>that</em> a
 * sample from a leaking module's packages survived, just without the chain.
 *
 * <p>Field access uses a generic walk over {@link RecordedObject#getFields()} rather than hardcoded
 * field names beyond the event-level ones: the exact nested shape of {@code
 * jdk.types.OldObject}/{@code jdk.types.Reference} isn't part of the public Javadoc, so guessing
 * field names risks silently breaking on a different JDK build. Confirmed empirically instead: a
 * sample's chain is {@code event.object.referrer.object.referrer...} until a null {@code referrer}
 * (a GC root), with {@code referrer.field}/{@code referrer.array} naming how the parent holds the
 * reference.
 */
final class OldObjectSampleCorrelator implements AutoCloseable {

  private static final int MAX_CHAIN_DEPTH = 8;

  /** Must match {@code AgentMain.LEAK_DETECTION_JFR_FLAG}'s {@code name=} value. */
  private static final String RECORDING_NAME = "gimle-leak-detection";

  private final Recording recording;

  OldObjectSampleCorrelator() {
    Recording found = null;
    try {
      found =
          FlightRecorder.getFlightRecorder().getRecordings().stream()
              .filter(r -> RECORDING_NAME.equals(r.getName()))
              .findFirst()
              .orElse(null);
    } catch (RuntimeException e) {
      // JFR unavailable/disabled in this environment: correlation degrades to "no path found",
      // it never fails leak detection itself.
    }
    this.recording = found;
  }

  Optional<String> findRetainingPath(Set<String> modulePackages) {
    if (recording == null) {
      return Optional.empty();
    }
    Path dump = null;
    try {
      dump = Files.createTempFile("gimle-oldobjectsample-", ".jfr");
      recording.dump(dump);
      return scanForMatch(dump, modulePackages);
    } catch (IOException | RuntimeException e) {
      return Optional.empty();
    } finally {
      if (dump != null) {
        try {
          Files.deleteIfExists(dump);
        } catch (IOException e) {
          // best-effort cleanup of a temp file
        }
      }
    }
  }

  private static Optional<String> scanForMatch(Path dump, Set<String> modulePackages)
      throws IOException {
    try (RecordingFile file = new RecordingFile(dump)) {
      while (file.hasMoreEvents()) {
        RecordedEvent event = file.readEvent();
        if (!"jdk.OldObjectSample".equals(event.getEventType().getName())) {
          continue;
        }
        if (!event.hasField("object")
            || !(event.getValue("object") instanceof RecordedObject object)) {
          continue;
        }
        List<RecordedObject> chain = buildChain(object);
        // The leaked module's own signature in the chain is often a referring wrapper (e.g. a
        // cache entry) rather than the sampled leaf itself (frequently a bare byte[]/Object[],
        // which JFR's size-weighted sampler favors regardless of which module allocated it) --
        // so every link in the chain is checked, not just the sampled object's own class.
        if (chain.stream().anyMatch(o -> modulePackages.contains(packageOf(o)))) {
          return Optional.of(describeChain(chain));
        }
      }
    }
    return Optional.empty();
  }

  private static List<RecordedObject> buildChain(RecordedObject leaf) {
    List<RecordedObject> chain = new ArrayList<>();
    chain.add(leaf);
    Object referrer = safeGet(leaf, "referrer");
    int depth = 0;
    while (referrer instanceof RecordedObject ref && depth < MAX_CHAIN_DEPTH) {
      Object held = safeGet(ref, "object");
      if (!(held instanceof RecordedObject next)) {
        break;
      }
      chain.add(next);
      referrer = safeGet(next, "referrer");
      depth++;
    }
    return chain;
  }

  private static String describeChain(List<RecordedObject> chain) {
    return chain.stream()
        .map(link -> Optional.ofNullable(extractClassName(link)).orElse("<unknown>"))
        .collect(Collectors.joining(" <- "));
  }

  private static String packageOf(RecordedObject object) {
    String className = extractClassName(object);
    if (className == null) {
      return "";
    }
    int lastDot = className.lastIndexOf('.');
    return lastDot < 0 ? "" : className.substring(0, lastDot);
  }

  private static String extractClassName(RecordedObject object) {
    Object type = safeGet(object, "type");
    if (type instanceof RecordedClass recordedClass) {
      return recordedClass.getName();
    }
    return null;
  }

  private static Object safeGet(RecordedObject object, String fieldName) {
    try {
      return object.hasField(fieldName) ? object.getValue(fieldName) : null;
    } catch (RuntimeException e) {
      return null;
    }
  }

  @Override
  public void close() {
    // Doesn't own `recording`'s lifecycle -- it's the worker JVM's own launch-time recording,
    // stopped by JVM shutdown, not by this correlator.
  }
}
