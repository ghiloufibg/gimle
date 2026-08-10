package com.gimle.core.logging;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.encoder.EncoderBase;
import java.util.Locale;

/**
 * The encoder Logback actually attaches to the shared {@code CONSOLE} appender ({@code
 * logback.xml}), picking between {@link JsonLogEncoder} and {@link PrettyLogEncoder} once, at
 * {@link #start()}, rather than choosing a format up front the way the two delegates' own
 * declarative wiring used to.
 *
 * <p><b>Why this has to stay JSON when a worker is spawned as a subprocess.</b> {@code
 * gimle-agent}'s own {@code WorkerProcessSupervisor.drainOutput} reads a worker's raw piped stdout
 * live and tells a structured line apart from unstructured output (a JVM banner before Logback
 * initializes, a stray {@code System.out.println}) by attempting to JSON-parse each one -- a
 * structured line is already captured by the worker's own {@code PlatformFileAppender} (a
 * completely separate Logback appender, unaffected by whatever this class picks), so re-logging it
 * from the drained pipe in a different format would just duplicate it under a wrong category. This
 * is the one thing pretty console output must never break, and it does so by construction rather
 * than by a flag {@code gimle-agent} has to remember to pass: {@link System#console()} is {@code
 * null} whenever stdout is piped or redirected to a file -- true for every worker {@code
 * WorkerProcessSupervisor} spawns, and equally true for every process {@code gimle-maven-plugin}'s
 * {@code BootstrapMojo}/{@code GreeterSmokeTestIT} launch (both redirect output to a log file,
 * never {@code inheritIO()}) -- so the auto-detected default already stays JSON on every one of
 * those paths with no extra wiring, and only switches to pretty when a real human is watching an
 * interactive terminal directly.
 *
 * <p>{@code -Dgimle.log.console=json|pretty} forces a specific choice regardless of {@link
 * System#console()} -- useful when piping a directly-run process's output through something like
 * {@code tee} while still wanting the pretty form on the terminal it's also going to.
 */
public final class ConsoleLogEncoder extends EncoderBase<ILoggingEvent> {

  private static final String MODE_PROPERTY = "gimle.log.console";

  private EncoderBase<ILoggingEvent> delegate;

  @Override
  public void start() {
    delegate = useJson() ? new JsonLogEncoder() : new PrettyLogEncoder();
    delegate.setContext(getContext());
    delegate.start();
    super.start();
  }

  @Override
  public void stop() {
    if (delegate != null) {
      delegate.stop();
    }
    super.stop();
  }

  @Override
  public byte[] headerBytes() {
    return delegate.headerBytes();
  }

  @Override
  public byte[] footerBytes() {
    return delegate.footerBytes();
  }

  @Override
  public byte[] encode(ILoggingEvent event) {
    return delegate.encode(event);
  }

  private static boolean useJson() {
    String override = System.getProperty(MODE_PROPERTY);
    if (override != null) {
      switch (override.toLowerCase(Locale.ROOT)) {
        case "json" -> {
          return true;
        }
        case "pretty", "text", "plain" -> {
          return false;
        }
        default -> {
          /* auto */
        }
      }
    }
    return System.console() == null;
  }
}
