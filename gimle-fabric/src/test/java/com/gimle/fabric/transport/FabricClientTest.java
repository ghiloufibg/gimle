package com.gimle.fabric.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.core.module.ModuleId;
import com.gimle.core.module.Version;
import com.gimle.fabric.registry.Greeter;
import com.gimle.fabric.trace.TraceContext;
import com.gimle.module.lifecycle.SimpleServiceRegistry;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.SocketTimeoutException;
import java.nio.channels.ServerSocketChannel;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * {@link FabricClient#call} previously had no bound at all on connect+write+read: a peer that
 * accepted a connection and then never responded (wedged, not refused/reset) left a caller hanging
 * indefinitely -- the gap {@link FabricClient#DEFAULT_TIMEOUT}/the explicit-{@code Duration}
 * overload closes. Uses a real {@link ServerSocketChannel} that accepts and then does nothing,
 * rather than a mock, since the property under test is genuinely about blocking I/O timing.
 */
class FabricClientTest {

  private static final ModuleId OWNER =
      new ModuleId("com.gimle.example.greeter", Version.parse("1.0.0"));
  private static final TraceContext TRACE = new TraceContext(1L, 2L, 3L, (byte) 1);

  private ServerSocketChannel wedgedServer;
  private ExecutorService acceptThread;
  private FabricServer realServer;

  @AfterEach
  void tearDown() throws IOException {
    if (wedgedServer != null) {
      wedgedServer.close();
    }
    if (acceptThread != null) {
      acceptThread.shutdownNow();
    }
    if (realServer != null) {
      realServer.close();
    }
  }

  private static FabricFrame.InvokeRequest invokeGreet(String arg) {
    return new FabricFrame.InvokeRequest(
        1L,
        TRACE,
        Greeter.class.getName(),
        "greet",
        new String[] {"java.lang.String"},
        ObjectMarshalling.serialize(new Object[] {arg}));
  }

  @Test
  @Timeout(10)
  void a_peer_that_accepts_but_never_responds_times_out_within_the_configured_bound()
      throws Exception {
    wedgedServer = ServerSocketChannel.open();
    wedgedServer.bind(new InetSocketAddress("127.0.0.1", 0));
    InetSocketAddress address = (InetSocketAddress) wedgedServer.getLocalAddress();

    // Accepts the connection (so the client's connect() succeeds) and then does nothing at all --
    // the client's read() is left blocked exactly as a wedged real peer would leave it.
    acceptThread = Executors.newVirtualThreadPerTaskExecutor();
    acceptThread.submit(
        () -> {
          try {
            wedgedServer.accept();
          } catch (IOException ignored) {
            // test teardown closing the server channel unblocks this; nothing to report
          }
        });

    Duration timeout = Duration.ofMillis(300);
    long start = System.nanoTime();
    IOException thrown =
        assertThrows(
            IOException.class, () -> FabricClient.call(address, invokeGreet("world"), timeout));
    long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

    assertTrue(
        thrown instanceof SocketTimeoutException,
        "expected a SocketTimeoutException, got: " + thrown);
    // Generous upper bound (the configured timeout plus real scheduling slack) -- the point is
    // "bounded, not indefinite", not pinning an exact millisecond figure.
    assertTrue(
        elapsedMillis < 5000,
        "expected the call to fail close to the "
            + timeout
            + " timeout, took "
            + elapsedMillis
            + "ms");
  }

  @Test
  @Timeout(10)
  void a_normal_call_completes_successfully_within_a_generous_timeout() throws Exception {
    SimpleServiceRegistry registry = new SimpleServiceRegistry();
    registry.register(OWNER, Greeter.class, name -> "hello:" + name);
    realServer =
        new FabricServer(
            registry,
            Greeter.class.getClassLoader(),
            id -> Optional.empty(),
            id -> Optional.empty());
    InetSocketAddress address =
        (InetSocketAddress) realServer.listen(new InetSocketAddress("127.0.0.1", 0));

    FabricFrame response = FabricClient.call(address, invokeGreet("world"), Duration.ofSeconds(5));

    assertEquals(
        "hello:world",
        ObjectMarshalling.deserialize(((FabricFrame.InvokeResponse) response).serializedReturn()));
  }

  @Test
  @Timeout(10)
  void a_refused_connection_fails_fast_without_waiting_out_the_timeout() throws Exception {
    // Bind and immediately close: the port is (almost certainly) still refusing connections
    // afterward, giving a real "connection refused" without a second process.
    InetSocketAddress address;
    try (ServerSocket probe = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))) {
      address = new InetSocketAddress("127.0.0.1", probe.getLocalPort());
    }

    Duration timeout = Duration.ofSeconds(5);
    long start = System.nanoTime();
    assertThrows(
        IOException.class, () -> FabricClient.call(address, invokeGreet("world"), timeout));
    long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

    assertTrue(
        elapsedMillis < 4000,
        "a refused connection should fail immediately, not wait out the timeout; took "
            + elapsedMillis
            + "ms");
  }
}
