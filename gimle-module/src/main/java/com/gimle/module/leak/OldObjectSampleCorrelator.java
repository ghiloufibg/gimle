package com.gimle.module.leak;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Optional;
import java.util.Set;
import jdk.jfr.consumer.RecordedClass;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedObject;
import jdk.jfr.consumer.RecordingStream;

/**
 * Best-effort retaining-path attribution via JFR's {@code jdk.OldObjectSample} event. Enabling that
 * event only captures object identity/type/age; the actual reference-chain-to-GC-root data
 * additionally requires {@code path-to-gc-roots=true}, which is a recording-launch option (jcmd /
 * {@code -XX:StartFlightRecording}), not something settable through the public {@code Recording}
 * API in-process — so this correlator can only surface a chain when the worker JVM was launched
 * with that flag (a Phase 2 concern, once a worker launcher exists to set it). Without it, a match
 * still confirms *that* a sample from a leaking module's packages survived, just without the chain
 * — the degraded-but-honest behavior Phase 1 commits to, not a stub.
 *
 * <p>Field access uses a generic walk over {@link RecordedObject#getFields()} rather than hardcoded
 * field names beyond the event-level ones (confirmed via the JFR event reference): the exact nested
 * shape of {@code jdk.types.OldObject} isn't part of the public Javadoc, so guessing field names
 * risks silently matching nothing on some JDK build.
 */
final class OldObjectSampleCorrelator implements AutoCloseable {

  private static final int MAX_SAMPLES = 256;
  private static final int MAX_CHAIN_DEPTH = 6;

  private final Deque<Sample> recentSamples = new ArrayDeque<>();
  private final RecordingStream recordingStream;

  OldObjectSampleCorrelator() {
    RecordingStream stream;
    try {
      stream = new RecordingStream();
      stream.enable("jdk.OldObjectSample");
      stream.onEvent("jdk.OldObjectSample", this::on_sample);
      stream.startAsync();
    } catch (RuntimeException e) {
      // JFR unavailable/disabled in this environment: correlation degrades to "no path found",
      // it never fails leak detection itself.
      stream = null;
    }
    this.recordingStream = stream;
  }

  Optional<String> find_retaining_path(Set<String> modulePackages) {
    synchronized (recentSamples) {
      return recentSamples.stream()
          .filter(sample -> modulePackages.contains(sample.packageName()))
          .findFirst()
          .map(Sample::description);
    }
  }

  private void on_sample(RecordedEvent event) {
    if (!event.hasField("object") || !(event.getValue("object") instanceof RecordedObject object)) {
      return;
    }
    String className = extract_class_name(object);
    if (className == null) {
      return;
    }
    int lastDot = className.lastIndexOf('.');
    String packageName = lastDot < 0 ? "" : className.substring(0, lastDot);
    String description = describe(object, 0);
    synchronized (recentSamples) {
      recentSamples.addLast(new Sample(packageName, description));
      while (recentSamples.size() > MAX_SAMPLES) {
        recentSamples.removeFirst();
      }
    }
  }

  private static String extract_class_name(RecordedObject object) {
    for (var field : object.getFields()) {
      if (safe_get(object, field.getName()) instanceof RecordedClass recordedClass) {
        return recordedClass.getName();
      }
    }
    return null;
  }

  private static String describe(RecordedObject object, int depth) {
    String className = extract_class_name(object);
    StringBuilder text = new StringBuilder(className != null ? className : "<unknown>");
    if (depth < MAX_CHAIN_DEPTH) {
      for (var field : object.getFields()) {
        Object value = safe_get(object, field.getName());
        if (value instanceof RecordedObject nested && !(value instanceof RecordedClass)) {
          text.append(" <- ").append(describe(nested, depth + 1));
          break;
        }
      }
    }
    return text.toString();
  }

  private static Object safe_get(RecordedObject object, String fieldName) {
    try {
      return object.getValue(fieldName);
    } catch (RuntimeException e) {
      return null;
    }
  }

  @Override
  public void close() {
    if (recordingStream != null) {
      recordingStream.close();
    }
  }

  private record Sample(String packageName, String description) {}
}
