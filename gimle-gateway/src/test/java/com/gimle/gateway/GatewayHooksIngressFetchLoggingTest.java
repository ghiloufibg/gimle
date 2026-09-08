package com.gimle.gateway;

import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.io.IOException;
import org.junit.jupiter.api.Test;

/**
 * NET-02 (Vault, gateway half): a message-less exception used to render as "...: null" with nothing
 * else to go on, so an operator staring at a genuinely unreachable control plane's logs had no idea
 * what actually failed. {@link GatewayHooks#logIngressFetchFailure} is exercised directly with a
 * bare {@link IOException} (its {@code getMessage()} is null by construction, the same shape a real
 * closed-channel/reset-connection failure takes) rather than through a live network failure, since
 * forcing a genuinely message-less exception out of a real socket is not something a test can
 * reliably control.
 */
class GatewayHooksIngressFetchLoggingTest {

  @Test
  void a_message_less_ingress_fetch_failure_still_names_the_exception_type_and_endpoint() {
    ch.qos.logback.classic.Logger logger =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(GatewayHooks.class);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    try {
      new GatewayHooks().logIngressFetchFailure("control-plane.internal:8443", new IOException());

      assertTrue(appender.list.size() >= 1, "the fetch failure should have produced a log line");
      String formatted = appender.list.get(appender.list.size() - 1).getFormattedMessage();
      assertTrue(
          formatted.contains("IOException"),
          "the log line should name the exception type: " + formatted);
      assertTrue(
          formatted.contains("control-plane.internal:8443"),
          "the log line should name the endpoint that could not be reached: " + formatted);
    } finally {
      logger.detachAppender(appender);
    }
  }
}
