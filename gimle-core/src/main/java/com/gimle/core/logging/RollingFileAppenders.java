package com.gimle.core.logging;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.Context;
import ch.qos.logback.core.rolling.FixedWindowRollingPolicy;
import ch.qos.logback.core.rolling.RollingFileAppender;
import ch.qos.logback.core.rolling.SizeBasedTriggeringPolicy;
import ch.qos.logback.core.util.Duration;
import ch.qos.logback.core.util.FileSize;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Builds a size/count-rotated {@link RollingFileAppender}, matching Kubernetes' own kubelet
 * container-log rotation exactly: rotate by size ({@code --container-log-max-size}, default 10Mi
 * here via {@code gimle.log.maxFileSizeBytes}), keep a fixed number of rotated copies ({@code
 * --container-log-max-files}, default 5 here via {@code gimle.log.maxFiles}), no time-based
 * retention, no compression -- kubelet has neither. {@link PlatformFileAppender} and {@link
 * InstanceSiftingFileAppender} both delegate their actual write+rotate+retain work to one of these
 * rather than writing bytes by hand, reusing Logback's own mature rotation engine (it already does
 * close-current/rename/reopen-fresh safely, which matters most on Windows, where renaming a file
 * out from under an open handle is stricter than POSIX) instead of a hand-rolled rename loop.
 *
 * <p>{@code FixedWindowRollingPolicy}'s {@code fileNamePattern: <file>.%i} renames files on each
 * rollover (today's {@code .1} becomes tomorrow's {@code .2}), which is exactly why {@link
 * LogFileReader}'s cursor is a timestamp, not a file index or byte offset -- a cursor built from
 * either would silently point at different content once a rotation happens between two reads.
 */
final class RollingFileAppenders {

  private static final long DEFAULT_MAX_FILE_SIZE_BYTES = 10L * 1024 * 1024;

  private RollingFileAppenders() {}

  record Handle(RollingFileAppender<ILoggingEvent> appender) {
    void close() {
      appender.stop();
    }
  }

  static Handle open(Context context, Path file) {
    if (file.getParent() != null) {
      try {
        Files.createDirectories(file.getParent());
      } catch (IOException e) {
        throw new UncheckedIOException("failed to create log directory " + file.getParent(), e);
      }
    }
    long maxFileSize = Long.getLong("gimle.log.maxFileSizeBytes", DEFAULT_MAX_FILE_SIZE_BYTES);
    int maxFiles = LogFileReader.configuredMaxFiles();

    RollingFileAppender<ILoggingEvent> appender = new RollingFileAppender<>();
    appender.setContext(context);
    appender.setFile(file.toString());
    appender.setEncoder(new JsonLogEncoder());

    FixedWindowRollingPolicy rollingPolicy = new FixedWindowRollingPolicy();
    rollingPolicy.setContext(context);
    rollingPolicy.setParent(appender);
    rollingPolicy.setFileNamePattern(file + ".%i");
    rollingPolicy.setMinIndex(1);
    rollingPolicy.setMaxIndex(Math.max(1, maxFiles - 1));
    rollingPolicy.start();

    SizeBasedTriggeringPolicy<ILoggingEvent> triggeringPolicy = new SizeBasedTriggeringPolicy<>();
    triggeringPolicy.setContext(context);
    triggeringPolicy.setMaxFileSize(new FileSize(maxFileSize));
    // Disable SizeBasedTriggeringPolicy's default invocation gate (an exponential wall-clock-time
    // backoff on how often it actually stats the file, meant to keep a size check off the hot path
    // under very high throughput). Gimlé's real log volume is nowhere near where that
    // micro-optimization matters, and disabling it makes rotation happen exactly when the size cap
    // is crossed, not after an unpredictable delay -- a strictly better guarantee for an operator
    // relying on it to keep disk usage bounded.
    triggeringPolicy.setCheckIncrement(new Duration(0));
    triggeringPolicy.start();

    appender.setRollingPolicy(rollingPolicy);
    appender.setTriggeringPolicy(triggeringPolicy);
    appender.start();
    return new Handle(appender);
  }
}
