package com.gimle.core.logging;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.UnsynchronizedAppenderBase;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;

/**
 * Writes every PLATFORM-category line (this process's own component code -- API server, scheduler,
 * reconcilers, agent supervisor, worker runtime/scheduler) to one rolling-free append file.
 * Deliberately skips APPLICATION lines (both {@link InstanceMdcKeys#DEPLOYMENT_NAME} and {@link
 * InstanceMdcKeys#INSTANCE_INDEX} present in MDC) -- those are {@link
 * InstanceSiftingFileAppender}'s job, attached alongside this one in a worker process.
 */
public final class PlatformFileAppender extends UnsynchronizedAppenderBase<ILoggingEvent> {

  private final Path file;
  private final JsonLogEncoder encoder = new JsonLogEncoder();
  private OutputStream out;

  public PlatformFileAppender(Path file) {
    this.file = file;
  }

  @Override
  public void start() {
    try {
      if (file.getParent() != null) {
        Files.createDirectories(file.getParent());
      }
      out = Files.newOutputStream(file, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    } catch (IOException e) {
      addError("failed to open platform log file " + file, e);
      return;
    }
    super.start();
  }

  @Override
  protected void append(ILoggingEvent event) {
    if (isInstanceScoped(event)) {
      return;
    }
    byte[] bytes = encoder.encode(event);
    synchronized (this) {
      try {
        out.write(bytes);
        out.flush();
      } catch (IOException e) {
        addError("failed to write platform log line to " + file, e);
      }
    }
  }

  private static boolean isInstanceScoped(ILoggingEvent event) {
    Map<String, String> mdc = event.getMDCPropertyMap();
    return mdc.get(InstanceMdcKeys.DEPLOYMENT_NAME) != null
        && mdc.get(InstanceMdcKeys.INSTANCE_INDEX) != null;
  }

  @Override
  public void stop() {
    super.stop();
    if (out != null) {
      try {
        out.close();
      } catch (IOException ignored) {
        // best-effort close on shutdown
      }
    }
  }
}
