package com.gimle.core.logging;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.UnsynchronizedAppenderBase;
import java.nio.file.Path;
import java.util.Map;

/**
 * Writes every PLATFORM-category line (this process's own component code -- API server, scheduler,
 * reconcilers, agent supervisor, worker runtime/scheduler) to one size/count-rotated file (see
 * {@link RollingFileAppenders}, same pattern as Kubernetes' kubelet: rotate by size, keep a fixed
 * number of old copies, no time-based retention). Deliberately skips APPLICATION lines (both {@link
 * InstanceMdcKeys#DEPLOYMENT_NAME} and {@link InstanceMdcKeys#INSTANCE_INDEX} present in MDC) --
 * those are {@link InstanceSiftingFileAppender}'s job, attached alongside this one in a worker
 * process.
 */
public final class PlatformFileAppender extends UnsynchronizedAppenderBase<ILoggingEvent> {

  private final Path file;
  private RollingFileAppenders.Handle rolling;

  public PlatformFileAppender(Path file) {
    this.file = file;
  }

  @Override
  public void start() {
    rolling = RollingFileAppenders.open(getContext(), file);
    super.start();
  }

  @Override
  protected void append(ILoggingEvent event) {
    if (isInstanceScoped(event)) {
      return;
    }
    rolling.appender().doAppend(event);
  }

  private static boolean isInstanceScoped(ILoggingEvent event) {
    Map<String, String> mdc = event.getMDCPropertyMap();
    return mdc.get(InstanceMdcKeys.DEPLOYMENT_NAME) != null
        && mdc.get(InstanceMdcKeys.INSTANCE_INDEX) != null;
  }

  @Override
  public void stop() {
    super.stop();
    if (rolling != null) {
      rolling.close();
    }
  }
}
