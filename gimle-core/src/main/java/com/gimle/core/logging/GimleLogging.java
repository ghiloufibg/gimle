package com.gimle.core.logging;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import java.nio.file.Path;
import org.slf4j.LoggerFactory;

/**
 * Attaches the file appenders design §5 needs directly in Java, rather than through {@code
 * logback.xml} property substitution: each process's {@code Main} class calls these once, right
 * after parsing its own node id / role from CLI args, so the target file path is a plain
 * constructor argument under this code's own control -- no property-resolution-timing hazard
 * against Logback's own auto-configuration (which already ran, attaching the shared {@code CONSOLE}
 * appender, before any {@code main} method body executes). The one central {@code logback.xml} (see
 * its own header comment) stays exactly as-is; this only ever adds appenders on top, never replaces
 * it.
 */
public final class GimleLogging {

  private GimleLogging() {}

  public static void attachPlatformFileAppender(Path file) {
    LoggerContext context = context();
    PlatformFileAppender appender = new PlatformFileAppender(file);
    appender.setContext(context);
    appender.start();
    root(context).addAppender(appender);
  }

  public static InstanceLogCloser attachInstanceSiftingAppender(Path instancesDir) {
    LoggerContext context = context();
    InstanceSiftingFileAppender appender = new InstanceSiftingFileAppender(instancesDir);
    appender.setContext(context);
    appender.start();
    root(context).addAppender(appender);
    return appender;
  }

  private static LoggerContext context() {
    return (LoggerContext) LoggerFactory.getILoggerFactory();
  }

  private static Logger root(LoggerContext context) {
    return context.getLogger(Logger.ROOT_LOGGER_NAME);
  }
}
