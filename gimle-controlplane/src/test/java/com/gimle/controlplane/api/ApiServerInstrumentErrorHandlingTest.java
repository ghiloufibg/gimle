package com.gimle.controlplane.api;

import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.gimle.controlplane.testsupport.InProcessFafnir;
import com.gimle.controlplane.testsupport.InProcessStore;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpPrincipal;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

/**
 * {@code ApiServer#instrument} wraps every registered route's own handler, but until now only ever
 * caught {@code GimleRaftException} -- a bare {@code RuntimeException} escaping a handler (see
 * {@code handleCanI}, which catches only {@code IOException}) propagated straight into {@code
 * com.sun.net.httpserver}'s own internal dispatch code instead of this app's own logger, silently
 * losing the diagnostic trail. Exercised directly against the private wrapper via reflection with a
 * minimal fake {@link HttpExchange}, since reliably forcing a real handler into an unexpected
 * {@code RuntimeException} through legitimate HTTP input would be far more fragile than testing the
 * wrapper's own contract directly.
 */
class ApiServerInstrumentErrorHandlingTest {

  @TempDir(cleanup = CleanupMode.NEVER)
  Path tempDir;

  private InProcessStore inProcessStore;
  private InProcessFafnir inProcessFafnir;
  private ApiServer server;

  @BeforeEach
  void startServer() throws IOException {
    inProcessStore = InProcessStore.start(tempDir.resolve("store"));
    inProcessFafnir =
        InProcessFafnir.start(inProcessStore.client(), tempDir.resolve("keys/secret.key"));
    server = new ApiServer(inProcessStore.client(), 0, inProcessFafnir.client());
    server.start();
  }

  @AfterEach
  void stopServer() {
    server.close();
    inProcessFafnir.close();
    inProcessStore.close();
  }

  private HttpHandler wrapWithInstrument(HttpHandler delegate) throws Exception {
    Method instrument =
        ApiServer.class.getDeclaredMethod("instrument", String.class, HttpHandler.class);
    instrument.setAccessible(true);
    return (HttpHandler) instrument.invoke(server, "test-boom", delegate);
  }

  @Test
  void a_bare_runtime_exception_from_a_handler_is_logged_not_left_to_escape_raw() throws Exception {
    ch.qos.logback.classic.Logger apiServerLogger =
        (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(ApiServer.class);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    apiServerLogger.addAppender(appender);

    HttpHandler wrapped =
        wrapWithInstrument(
            exchange -> {
              throw new RuntimeException("boom-for-test");
            });
    FakeHttpExchange exchange = new FakeHttpExchange();

    try {
      // Reaching this line at all (rather than a RuntimeException propagating out of handle())
      // already proves the wrapper no longer lets a bare RuntimeException escape raw.
      wrapped.handle(exchange);
    } finally {
      apiServerLogger.detachAppender(appender);
    }

    assertTrue(
        exchange.responseCode >= 500, "expected a 500 response, got: " + exchange.responseCode);
    assertTrue(
        logMentionsTheException(appender),
        "the app's own logger must record the failure, saw: " + appender.list);
  }

  private static boolean logMentionsTheException(ListAppender<ILoggingEvent> appender) {
    return appender.list.stream()
        .anyMatch(
            event ->
                (event.getFormattedMessage() != null
                        && event.getFormattedMessage().contains("boom-for-test"))
                    || (event.getThrowableProxy() != null
                        && "boom-for-test".equals(event.getThrowableProxy().getMessage())));
  }

  /** The bare minimum of {@link HttpExchange} the instrumented call path actually touches. */
  private static final class FakeHttpExchange extends HttpExchange {
    private final Headers responseHeaders = new Headers();
    private final ByteArrayOutputStream responseBody = new ByteArrayOutputStream();
    int responseCode = -1;

    @Override
    public Headers getRequestHeaders() {
      return new Headers();
    }

    @Override
    public Headers getResponseHeaders() {
      return responseHeaders;
    }

    @Override
    public URI getRequestURI() {
      return URI.create("/test-boom");
    }

    @Override
    public String getRequestMethod() {
      return "GET";
    }

    @Override
    public HttpContext getHttpContext() {
      return null;
    }

    @Override
    public void close() {}

    @Override
    public InputStream getRequestBody() {
      return InputStream.nullInputStream();
    }

    @Override
    public OutputStream getResponseBody() {
      return responseBody;
    }

    @Override
    public void sendResponseHeaders(int status, long responseLength) {
      this.responseCode = status;
    }

    @Override
    public InetSocketAddress getRemoteAddress() {
      return new InetSocketAddress("127.0.0.1", 12345);
    }

    @Override
    public int getResponseCode() {
      return responseCode;
    }

    @Override
    public InetSocketAddress getLocalAddress() {
      return new InetSocketAddress("127.0.0.1", 8080);
    }

    @Override
    public String getProtocol() {
      return "HTTP/1.1";
    }

    @Override
    public Object getAttribute(String name) {
      return null;
    }

    @Override
    public void setAttribute(String name, Object value) {}

    @Override
    public void setStreams(InputStream i, OutputStream o) {}

    @Override
    public HttpPrincipal getPrincipal() {
      return null;
    }
  }
}
