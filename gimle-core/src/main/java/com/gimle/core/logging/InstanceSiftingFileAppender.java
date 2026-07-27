package com.gimle.core.logging;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.UnsynchronizedAppenderBase;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Sifts APPLICATION-category lines into one file per (deploymentName, instanceIndex), design §5.
 * Deliberately hand-rolled rather than Logback's own {@code SiftingAppender}: a worker JVM's live
 * key space is small (bounded by however many instances it hosts concurrently, Tier 1 density
 * aside) and this class's lifetime is driven by an authoritative uninstall signal already flowing
 * through {@code WorkerRuntime} (via {@link #closeInstance}), not by an idle-timeout heuristic a
 * generic sifting appender would need instead.
 */
public final class InstanceSiftingFileAppender extends UnsynchronizedAppenderBase<ILoggingEvent>
    implements InstanceLogCloser {

  private final Path instancesDir;
  private final JsonLogEncoder encoder = new JsonLogEncoder();
  private final Map<String, OutputStream> openFiles = new ConcurrentHashMap<>();

  public InstanceSiftingFileAppender(Path instancesDir) {
    this.instancesDir = instancesDir;
  }

  @Override
  protected void append(ILoggingEvent event) {
    Map<String, String> mdc = event.getMDCPropertyMap();
    String deploymentName = mdc.get(InstanceMdcKeys.DEPLOYMENT_NAME);
    String instanceIndex = mdc.get(InstanceMdcKeys.INSTANCE_INDEX);
    if (deploymentName == null || instanceIndex == null) {
      return; // a PLATFORM line -- PlatformFileAppender's job
    }
    String key = deploymentName + "-" + instanceIndex;
    OutputStream out = openFiles.computeIfAbsent(key, this::openFile);
    if (out == null) {
      return; // failed to open; already reported via addError
    }
    byte[] bytes = encoder.encode(event);
    synchronized (out) {
      try {
        out.write(bytes);
        out.flush();
      } catch (IOException e) {
        addError("failed to write instance log line for " + key, e);
      }
    }
  }

  private OutputStream openFile(String key) {
    try {
      Files.createDirectories(instancesDir);
      return Files.newOutputStream(
          instancesDir.resolve(key + ".log"), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    } catch (IOException e) {
      addError("failed to open instance log file for " + key, e);
      return null;
    }
  }

  @Override
  public void closeInstance(String deploymentName, int instanceIndex) {
    OutputStream out = openFiles.remove(deploymentName + "-" + instanceIndex);
    if (out == null) {
      return;
    }
    try {
      out.close();
    } catch (IOException e) {
      addError("failed to close instance log file for " + deploymentName + "-" + instanceIndex, e);
    }
  }

  @Override
  public void stop() {
    super.stop();
    for (OutputStream out : openFiles.values()) {
      try {
        out.close();
      } catch (IOException ignored) {
        // best-effort close on shutdown
      }
    }
    openFiles.clear();
  }
}
