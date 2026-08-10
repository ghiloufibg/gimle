package com.gimle.core.logging;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.encoder.EncoderBase;
import java.util.Locale;

/**
 * The encoder Logback actually attaches to the shared {@code CONSOLE} appender ({@code
 * logback.xml}), picking between {@link JsonLogEncoder} and {@link TextLogEncoder} once, at {@link
 * #start()}, rather than choosing a format up front the way the two delegates' own declarative
 * wiring used to.
 *
 * <p><b>Default is text, unconditionally -- this is not a heuristic.</b> Every process already
 * writes a complete, unconditional JSON trail to its own {@code *-platform.log} file via {@code
 * GimleLogging.attachPlatformFileAppender} -- a separate Logback appender, wired in Java, never
 * touching this class -- so nothing that reads structured logs (the log explorer's {@code
 * AgentLogServer}, {@code gimle-cli logs}) ever looks at {@code CONSOLE} output in the first place.
 * That means {@code CONSOLE} is free to default to the colored, human-friendly {@link
 * TextLogEncoder} for every process, every time, whether its stdout is attached to a real terminal
 * or redirected to a file for later {@code tail -f} (e.g. {@code gimle-maven-plugin}'s {@code
 * BootstrapMojo}, whose ANSI escapes render correctly for whoever tails that file from a
 * color-capable terminal).
 *
 * <p><b>The one exception: a worker spawned as a supervised subprocess.</b> {@code gimle-agent}'s
 * {@code WorkerProcessSupervisor.drainOutput} reads a worker's raw piped stdout live and tells a
 * structured line apart from unstructured output (a JVM banner before Logback initializes, a stray
 * {@code System.out.println}) by attempting to JSON-parse each one -- a structured line is already
 * captured by the worker's own {@code PlatformFileAppender}, so re-logging it from the drained pipe
 * in a different format would just duplicate it under a wrong category. {@code AgentMain} keeps
 * this correct not by guessing at the worker's stdout shape, but by passing {@code
 * -Dgimle.log.console=json} explicitly on every worker it spawns (see {@code
 * AgentMain#buildWorkerCommand}) -- an intentional, visible override rather than an implicit
 * consequence of the pipe happening to make {@link System#console()} return {@code null}.
 *
 * <p>{@code -Dgimle.log.console=json|text} forces a specific choice for any other process too --
 * useful for scripting against a directly-run process's own stdout, or reverting to JSON on a
 * terminal that mishandles the ANSI escapes.
 */
public final class ConsoleLogEncoder extends EncoderBase<ILoggingEvent> {

  private static final String MODE_PROPERTY = "gimle.log.console";

  private EncoderBase<ILoggingEvent> delegate;

  @Override
  public void start() {
    delegate = useJson() ? new JsonLogEncoder() : new TextLogEncoder();
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
    if (override == null) {
      return false; // default: text, always -- see this class's own javadoc for why that's safe.
    }
    return switch (override.toLowerCase(Locale.ROOT)) {
      case "json" -> true;
      case "text", "pretty", "plain" -> false;
      default -> false;
    };
  }
}
