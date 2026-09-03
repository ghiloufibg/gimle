package com.gimle.fabric.registry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.gimle.core.module.ModuleId;
import com.gimle.core.module.ServiceExport;
import com.gimle.core.module.Version;
import com.gimle.fabric.catalog.ServiceCatalog;
import com.gimle.fabric.cluster.MemberId;
import com.gimle.fabric.transport.FabricCodec;
import com.gimle.fabric.transport.FabricFrame;
import com.gimle.fabric.transport.FabricServer;
import com.gimle.module.lifecycle.SimpleServiceRegistry;
import com.gimle.observability.WorkerMetrics;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.channels.Channels;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.parallel.ResourceAccessMode;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

/**
 * The two kinds of transport failure a cross-hop call can hit, and the deliberately different
 * things the client is allowed to do about each: a connection that was never established proves the
 * call did not run, so failing over is safe whatever the method does, while a failure after the
 * request was written leaves the outcome unknown and may only be retried for a method whose author
 * declared it repeatable.
 *
 * <p>Every backend here is a real socket -- a refused port, a peer that hangs up mid-call, a real
 * {@link FabricServer} -- since the property under test is entirely about which side of the write
 * the failure landed on, which a stubbed transport could only assert by assumption.
 */
// Reads gimle.transport.protocol (through FabricClient/FabricServer) without ever setting it: a
// READ lock lets these plaintext classes run concurrently with each other while excluding any
// class that mutates the JVM-global TLS properties mid-run (see FabricTransportTlsTest).
@ResourceLock(value = Resources.SYSTEM_PROPERTIES, mode = ResourceAccessMode.READ)
class FabricServiceRegistryRetryTest {

  private static final ServiceExport GREETER_EXPORT =
      new ServiceExport(Greeter.class.getName(), Version.parse("1.0.0"));
  private static final ServiceExport RETRYABLE_EXPORT =
      new ServiceExport(RetryableGreeter.class.getName(), Version.parse("1.0.0"));
  private static final ModuleId OWNER =
      new ModuleId("com.gimle.example.greeter", Version.parse("1.0.0"));

  private final MemberId selfNode =
      new MemberId("node-a", new InetSocketAddress("127.0.0.1", 7946));
  private final MemberId otherNode =
      new MemberId("node-b", new InetSocketAddress("127.0.0.1", 7947));

  private final List<FabricServer> servers = new ArrayList<>();
  private final List<ServerSocketChannel> rawServers = new ArrayList<>();

  @AfterEach
  void tearDown() throws IOException {
    servers.forEach(FabricServer::close);
    for (ServerSocketChannel raw : rawServers) {
      raw.close();
    }
  }

  private FabricServiceRegistry newRegistry(
      ServiceCatalog catalog, Optional<WorkerMetrics> metrics) {
    return new FabricServiceRegistry(
        selfNode,
        "worker-self",
        new SimpleServiceRegistry(),
        catalog,
        owner -> List.of(GREETER_EXPORT, RETRYABLE_EXPORT),
        message -> {},
        Greeter.class.getClassLoader(),
        4,
        0.5,
        Duration.ofSeconds(5),
        Optional.empty(),
        0.5,
        false,
        metrics);
  }

  private <T> InetSocketAddress startBackend(Class<T> iface, T impl) throws IOException {
    SimpleServiceRegistry backing = new SimpleServiceRegistry();
    backing.register(OWNER, iface, impl);
    FabricServer server = new FabricServer(backing, iface.getClassLoader());
    servers.add(server);
    return (InetSocketAddress) server.listen(new InetSocketAddress("127.0.0.1", 0));
  }

  /**
   * Bound and immediately closed, so connecting to it is refused outright -- the same trick {@code
   * FabricClientTest} uses to produce a genuine connect-time failure without a second process.
   */
  private static InetSocketAddress refusedAddress() throws IOException {
    try (ServerSocket probe = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))) {
      return new InetSocketAddress("127.0.0.1", probe.getLocalPort());
    }
  }

  /**
   * Accepts, reads the whole request frame, records its correlation id, and hangs up without
   * answering -- the connection was established and the request was written, so the caller cannot
   * know whether the target ran it.
   */
  private InetSocketAddress startHangUpAfterReading(List<Long> seenCorrelationIds)
      throws IOException {
    ServerSocketChannel listener = ServerSocketChannel.open();
    listener.bind(new InetSocketAddress("127.0.0.1", 0));
    rawServers.add(listener);
    Thread.ofVirtual()
        .start(
            () -> {
              while (listener.isOpen()) {
                try (SocketChannel connection = listener.accept()) {
                  FabricFrame frame = FabricCodec.read(Channels.newInputStream(connection));
                  if (frame != null) {
                    seenCorrelationIds.add(frame.correlationId());
                  }
                } catch (IOException | RuntimeException e) {
                  return; // teardown closed the listener, or the peer vanished; nothing to report
                }
              }
            });
    return (InetSocketAddress) listener.getLocalAddress();
  }

  private void registerSameMachine(
      ServiceCatalog catalog, ServiceExport export, InetSocketAddress address) {
    catalog.localRegister(selfNode, "worker-other", OWNER, export, Optional.empty(), address);
  }

  private void registerRemote(
      ServiceCatalog catalog, ServiceExport export, InetSocketAddress address) {
    catalog.localRegister(otherNode, "worker-b", OWNER, export, Optional.empty(), address);
  }

  /**
   * The same-machine tier is preferred while both tiers are idle, so the refused endpoint below is
   * reliably attempted first and the live remote one is only reached by failing over.
   */
  @Test
  @Timeout(20)
  void a_connect_failure_fails_over_to_another_endpoint_even_for_a_non_idempotent_method()
      throws IOException {
    ServiceCatalog catalog = new ServiceCatalog();
    registerSameMachine(catalog, GREETER_EXPORT, refusedAddress());
    registerRemote(catalog, GREETER_EXPORT, startBackend(Greeter.class, name -> "live:" + name));

    FabricServiceRegistry registry = newRegistry(catalog, Optional.empty());
    Greeter greeter = registry.lookup(Greeter.class).orElseThrow();

    assertEquals("live:x", greeter.greet("x"));
  }

  @Test
  @Timeout(20)
  void a_failure_after_the_request_was_sent_is_not_retried_for_a_non_idempotent_method()
      throws IOException {
    AtomicInteger liveInvocations = new AtomicInteger();
    ServiceCatalog catalog = new ServiceCatalog();
    registerSameMachine(
        catalog, GREETER_EXPORT, startHangUpAfterReading(new CopyOnWriteArrayList<>()));
    registerRemote(
        catalog,
        GREETER_EXPORT,
        startBackend(
            Greeter.class,
            name -> {
              liveInvocations.incrementAndGet();
              return "live:" + name;
            }));

    FabricServiceRegistry registry = newRegistry(catalog, Optional.empty());
    Greeter greeter = registry.lookup(Greeter.class).orElseThrow();

    assertThrows(UncheckedIOException.class, () -> greeter.greet("x"));
    assertEquals(
        0,
        liveInvocations.get(),
        "the surviving endpoint must not be dialed: the first attempt may already have executed");
  }

  @Test
  @Timeout(20)
  void a_failure_after_the_request_was_sent_is_retried_for_a_method_declared_idempotent()
      throws IOException {
    ServiceCatalog catalog = new ServiceCatalog();
    registerSameMachine(
        catalog, RETRYABLE_EXPORT, startHangUpAfterReading(new CopyOnWriteArrayList<>()));
    registerRemote(
        catalog, RETRYABLE_EXPORT, startBackend(RetryableGreeter.class, name -> "live:" + name));

    FabricServiceRegistry registry = newRegistry(catalog, Optional.empty());
    RetryableGreeter greeter = registry.lookup(RetryableGreeter.class).orElseThrow();

    assertEquals("live:x", greeter.greet("x"));
  }

  /**
   * The mechanism that makes retrying a possibly-executed call safe at all: both attempts carry one
   * correlation id, so a target that did run the first one recognizes the second as its retry
   * rather than a new call.
   */
  @Test
  @Timeout(20)
  void every_attempt_of_one_logical_call_carries_the_same_correlation_id() throws IOException {
    List<Long> firstSeen = new CopyOnWriteArrayList<>();
    List<Long> secondSeen = new CopyOnWriteArrayList<>();
    ServiceCatalog catalog = new ServiceCatalog();
    registerSameMachine(catalog, RETRYABLE_EXPORT, startHangUpAfterReading(firstSeen));
    registerRemote(catalog, RETRYABLE_EXPORT, startHangUpAfterReading(secondSeen));

    FabricServiceRegistry registry = newRegistry(catalog, Optional.empty());
    RetryableGreeter greeter = registry.lookup(RetryableGreeter.class).orElseThrow();

    assertThrows(UncheckedIOException.class, () -> greeter.greet("x"));
    assertEquals(1, firstSeen.size(), "the first endpoint should have seen exactly one attempt");
    assertEquals(1, secondSeen.size(), "the retry should have gone to the other endpoint");
    assertEquals(firstSeen.get(0), secondSeen.get(0));
  }

  /**
   * Before this, an endpoint's breaker opening left no trace anywhere -- the endpoint simply
   * stopped being selected, indistinguishable from a catalog that never learned about it or an
   * instance that never became ready. Both the meter and the log line assert the same transition,
   * because an operator reaches for whichever of the two their situation puts in front of them.
   */
  @Test
  @Timeout(20)
  void an_endpoints_breaker_opening_is_visible_as_a_meter_and_a_log_line() throws IOException {
    ServiceCatalog catalog = new ServiceCatalog();
    registerRemote(catalog, GREETER_EXPORT, refusedAddress());

    WorkerMetrics metrics = new WorkerMetrics();
    FabricServiceRegistry registry = newRegistry(catalog, Optional.of(metrics));
    ch.qos.logback.classic.Logger logger =
        (ch.qos.logback.classic.Logger)
            org.slf4j.LoggerFactory.getLogger(FabricServiceRegistry.class);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    try {
      String endpoint = "node-b/worker-b";
      assertEquals(
          0.0,
          metrics.circuitBreakerState(Greeter.class.getName(), endpoint),
          "a closed breaker reads as zero from the moment it is first tracked");

      // The breaker's window is 4 wide at a 50% error rate, so four consecutive refused calls are
      // what it takes to open it -- each lookup is a fresh single-attempt call, since the sole
      // endpoint has already been tried by the time a failover would pick one.
      for (int i = 0; i < 4; i++) {
        Greeter greeter = registry.lookup(Greeter.class).orElseThrow();
        assertThrows(UncheckedIOException.class, () -> greeter.greet("x"));
      }

      assertEquals(
          2.0,
          metrics.circuitBreakerState(Greeter.class.getName(), endpoint),
          "the gauge should report the OPEN level");
      assertEquals(
          1.0, metrics.circuitBreakerTransitionCount(Greeter.class.getName(), endpoint, "OPEN"));
      assertTrue(
          appender.list.stream()
              .anyMatch(
                  event ->
                      event.getFormattedMessage().contains("circuit breaker for")
                          && event.getFormattedMessage().contains(endpoint)
                          && event.getFormattedMessage().contains("opened")),
          "the OPEN transition should have been logged: " + formattedMessages(appender));
    } finally {
      logger.detachAppender(appender);
    }
  }

  private static List<String> formattedMessages(ListAppender<ILoggingEvent> appender) {
    return appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
  }
}
