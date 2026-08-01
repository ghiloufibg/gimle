package com.gimle.core.logging;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.UnsynchronizedAppenderBase;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Sifts APPLICATION-category lines into one size/count-rotated file per (deploymentName,
 * instanceIndex) -- see {@link RollingFileAppenders} for the rotation policy, matching Kubernetes'
 * kubelet: each instance rotates independently, not against a shared budget across every instance
 * this worker hosts. Deliberately hand-rolled rather than Logback's own {@code SiftingAppender}: a
 * worker JVM's live key space is small (bounded by however many instances it hosts concurrently,
 * Tier 1 density aside) and this class's lifetime is driven by an authoritative uninstall signal
 * already flowing through {@code WorkerRuntime} (via {@link #closeInstance}), not by an
 * idle-timeout heuristic a generic sifting appender would need instead.
 */
public final class InstanceSiftingFileAppender extends UnsynchronizedAppenderBase<ILoggingEvent>
    implements InstanceLogCloser {

  private final Path instancesDir;
  private final Map<String, RollingFileAppenders.Handle> open = new ConcurrentHashMap<>();

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
    RollingFileAppenders.Handle handle = open.computeIfAbsent(key, this::openFile);
    if (handle == null) {
      return; // failed to open; already reported via addError
    }
    handle.appender().doAppend(event);
  }

  private RollingFileAppenders.Handle openFile(String key) {
    try {
      return RollingFileAppenders.open(getContext(), instancesDir.resolve(key + ".log"));
    } catch (RuntimeException e) {
      addError("failed to open instance log file for " + key, e);
      return null;
    }
  }

  @Override
  public void closeInstance(String deploymentName, int instanceIndex) {
    RollingFileAppenders.Handle handle = open.remove(deploymentName + "-" + instanceIndex);
    if (handle != null) {
      handle.close();
    }
  }

  @Override
  public void stop() {
    super.stop();
    for (RollingFileAppenders.Handle handle : open.values()) {
      handle.close();
    }
    open.clear();
  }
}
