package com.gimle.fabric.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.core.exception.GimleFabricAuthorizationException;
import com.gimle.core.module.ModuleId;
import com.gimle.core.module.ServiceExport;
import com.gimle.core.module.Version;
import com.gimle.fabric.registry.Greeter;
import com.gimle.fabric.trace.TraceContext;
import com.gimle.module.lifecycle.ModuleContext;
import com.gimle.module.lifecycle.SimpleModuleContext;
import com.gimle.module.lifecycle.SimpleServiceRegistry;
import com.gimle.observability.WorkerMetrics;
import com.gimle.testkit.Await;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.opentelemetry.api.baggage.Baggage;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Proves real inbound fabric traffic actually goes through the target module's {@link
 * ModuleContext} in-flight counter and its {@link ModuleWorkExecutor} concurrency bound -- the gap
 * this class's {@code contextLookup}/{@code executorLookup} constructor parameters close. Uses a
 * hand-rolled {@link ModuleWorkExecutor} rather than the real {@code BoundedModuleScheduler}
 * ({@code gimle-worker} depends on {@code gimle-fabric}, not the other way around, so the real one
 * isn't reachable from here) -- deliberately the same semantics (semaphore-bounded, one virtual
 * thread per task), just self-contained.
 */
class FabricServerTest {

  private static final ModuleId OWNER =
      new ModuleId("com.gimle.example.greeter", Version.parse("1.0.0"));
  private static final TraceContext TRACE = new TraceContext(1L, 2L, 3L, (byte) 1);

  private FabricServer server;
  private ExecutorService clientThreads;

  @AfterEach
  void tearDown() {
    if (server != null) {
      server.close();
    }
    if (clientThreads != null) {
      clientThreads.shutdownNow();
    }
  }

  /**
   * Bounds concurrency the same way {@code BoundedModuleScheduler} does, without depending on it.
   */
  private static ModuleWorkExecutor boundedExecutor(int maxConcurrency) {
    Semaphore bound = new Semaphore(maxConcurrency);
    ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    return new ModuleWorkExecutor() {
      @Override
      public <T> Future<T> submit(Callable<T> task) {
        return executor.submit(
            () -> {
              bound.acquire();
              try {
                return task.call();
              } finally {
                bound.release();
              }
            });
      }
    };
  }

  private FabricFrame.InvokeRequest invokeGreet(String arg) {
    return new FabricFrame.InvokeRequest(
        1L,
        TRACE,
        Greeter.class.getName(),
        "greet",
        new String[] {"java.lang.String"},
        ObjectMarshalling.serialize(new Object[] {arg}));
  }

  private FabricFrame.InvokeRequest invokeGreet(String arg, Optional<String> callerTenantId) {
    return new FabricFrame.InvokeRequest(
        1L,
        TRACE,
        Greeter.class.getName(),
        "greet",
        new String[] {"java.lang.String"},
        ObjectMarshalling.serialize(new Object[] {arg}),
        callerTenantId);
  }

  @Test
  @Timeout(10)
  void baggage_from_the_caller_survives_an_inbound_call_into_the_handler() throws Exception {
    // Baggage isn't just decoded and discarded -- it must actually be the ambient
    // Baggage.current() the target module's own handler observes, the same way it would if the
    // call had never left the caller's own thread.
    SimpleServiceRegistry registry = new SimpleServiceRegistry();
    AtomicReference<String> observedUserId = new AtomicReference<>();
    registry.register(
        OWNER,
        Greeter.class,
        name -> {
          observedUserId.set(Baggage.current().getEntryValue("userId"));
          return "hello:" + name;
        });

    server = new FabricServer(registry, Greeter.class.getClassLoader());
    InetSocketAddress address =
        (InetSocketAddress) server.listen(new InetSocketAddress("127.0.0.1", 0));

    TraceContext traceWithBaggage = new TraceContext(1L, 2L, 3L, (byte) 1, "", "userId=alice");
    FabricFrame.InvokeRequest request =
        new FabricFrame.InvokeRequest(
            1L,
            traceWithBaggage,
            Greeter.class.getName(),
            "greet",
            new String[] {"java.lang.String"},
            ObjectMarshalling.serialize(new Object[] {"world"}));

    clientThreads = Executors.newVirtualThreadPerTaskExecutor();
    clientThreads.submit(() -> FabricClient.call(address, request)).get(5, TimeUnit.SECONDS);

    assertEquals("alice", observedUserId.get());
  }

  @Test
  void has_remote_span_distinguishes_a_real_caller_span_from_the_no_active_span_marker() {
    // FabricServiceRegistry#captureTrace's own "no active span at call time" wire representation:
    // all-zero trace/span IDs, the W3C spec's own reserved invalid values.
    assertFalse(FabricServer.hasRemoteSpan(new TraceContext(0L, 0L, 0L, (byte) 0)));
    assertTrue(FabricServer.hasRemoteSpan(TRACE));
    // Any one non-zero field is enough to mean "a real span was captured".
    assertTrue(FabricServer.hasRemoteSpan(new TraceContext(1L, 0L, 0L, (byte) 0)));
    assertTrue(FabricServer.hasRemoteSpan(new TraceContext(0L, 0L, 1L, (byte) 0)));
  }

  @Test
  @Timeout(10)
  void a_real_inbound_call_is_visible_in_the_targets_in_flight_count_while_it_runs()
      throws Exception {
    CountDownLatch started = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    SimpleServiceRegistry registry = new SimpleServiceRegistry();
    registry.register(
        OWNER,
        Greeter.class,
        name -> {
          started.countDown();
          try {
            release.await(5, TimeUnit.SECONDS);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
          return "hello:" + name;
        });

    ModuleContext ctx = new SimpleModuleContext(OWNER, registry);
    assertEquals(0, ctx.inFlightCount());

    server =
        new FabricServer(
            registry,
            Greeter.class.getClassLoader(),
            id -> Optional.of(ctx),
            id -> Optional.of(boundedExecutor(4)));
    InetSocketAddress address =
        (InetSocketAddress) server.listen(new InetSocketAddress("127.0.0.1", 0));

    clientThreads = Executors.newVirtualThreadPerTaskExecutor();
    Future<FabricFrame> call =
        clientThreads.submit(() -> FabricClient.call(address, invokeGreet("world")));

    assertTrue(started.await(5, TimeUnit.SECONDS));
    // The regression this test guards: beginRequest/endRequest previously had zero call sites
    // outside SimpleModuleContext's own definition, so drain's inFlightCount() could never
    // reflect genuine inbound fabric traffic -- only ever a hosted module's own hook code, which
    // never runs against this path.
    assertEquals(1, ctx.inFlightCount());

    release.countDown();
    FabricFrame response = call.get(5, TimeUnit.SECONDS);
    assertEquals(
        "hello:world",
        ObjectMarshalling.deserialize(((FabricFrame.InvokeResponse) response).serializedReturn()));
    assertEquals(0, ctx.inFlightCount());
  }

  @Test
  @Timeout(10)
  void concurrent_calls_are_bounded_by_the_targets_executor_not_run_unbounded() throws Exception {
    int maxConcurrency = 1;
    int callCount = 3;
    AtomicInteger concurrent = new AtomicInteger();
    AtomicInteger maxObservedConcurrent = new AtomicInteger();
    CountDownLatch allStarted = new CountDownLatch(callCount);
    CountDownLatch release = new CountDownLatch(1);

    SimpleServiceRegistry registry = new SimpleServiceRegistry();
    registry.register(
        OWNER,
        Greeter.class,
        name -> {
          int now = concurrent.incrementAndGet();
          maxObservedConcurrent.updateAndGet(max -> Math.max(max, now));
          allStarted.countDown();
          try {
            release.await(5, TimeUnit.SECONDS);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          } finally {
            concurrent.decrementAndGet();
          }
          return "hello:" + name;
        });

    // Constructed once and closed over, not rebuilt per lookup call: the real production wiring
    // (WorkerRuntime#schedulerFor) returns the same shared scheduler instance for a given module
    // on every call, which is what actually makes the bound apply across separate inbound
    // connections rather than each one getting its own private, trivially-satisfied semaphore.
    ModuleWorkExecutor sharedExecutor = boundedExecutor(maxConcurrency);
    server =
        new FabricServer(
            registry,
            Greeter.class.getClassLoader(),
            id -> Optional.empty(),
            id -> Optional.of(sharedExecutor));
    InetSocketAddress address =
        (InetSocketAddress) server.listen(new InetSocketAddress("127.0.0.1", 0));

    clientThreads = Executors.newVirtualThreadPerTaskExecutor();
    List<Future<FabricFrame>> calls =
        List.of(
            clientThreads.submit(() -> FabricClient.call(address, invokeGreet("a"))),
            clientThreads.submit(() -> FabricClient.call(address, invokeGreet("b"))),
            clientThreads.submit(() -> FabricClient.call(address, invokeGreet("c"))));

    // First wait for at least one call to have actually reached the service -- under load, zero
    // of the three client calls may have gotten there yet within a bare fixed sleep. Then hold
    // briefly: bounded to 1, at any instant while all three client calls are outstanding, only
    // one should ever be inside the service method -- give the (deliberately unbounded, if the
    // fix regresses) alternative a real chance to show itself before asserting.
    Await.until(() -> concurrent.get() >= 1, Duration.ofSeconds(5));
    Thread.sleep(200);
    assertEquals(1, concurrent.get());

    release.countDown();
    for (Future<FabricFrame> call : calls) {
      call.get(5, TimeUnit.SECONDS);
    }

    assertTrue(allStarted.await(5, TimeUnit.SECONDS));
    assertEquals(1, maxObservedConcurrent.get());
  }

  @Test
  @Timeout(10)
  void real_calls_are_recorded_in_the_targets_worker_metrics_including_errors() throws Exception {
    SimpleServiceRegistry registry = new SimpleServiceRegistry();
    registry.register(
        OWNER,
        Greeter.class,
        name -> {
          if (name.equals("boom")) {
            throw new IllegalStateException("boom");
          }
          return "hello:" + name;
        });

    SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    WorkerMetrics workerMetrics = new WorkerMetrics(meterRegistry);
    server =
        new FabricServer(
            registry,
            Greeter.class.getClassLoader(),
            id -> Optional.empty(),
            id -> Optional.empty(),
            Optional.of(workerMetrics));
    InetSocketAddress address =
        (InetSocketAddress) server.listen(new InetSocketAddress("127.0.0.1", 0));

    FabricClient.call(address, invokeGreet("world"));
    FabricClient.call(address, invokeGreet("boom"));

    assertEquals(
        2.0,
        meterRegistry
            .find("gimle.module.request.count")
            .tag("module", OWNER.name())
            .counter()
            .count());
    assertEquals(
        1.0,
        meterRegistry
            .find("gimle.module.request.errors")
            .tag("module", OWNER.name())
            .counter()
            .count());
  }

  private FabricServer serverWithExport(SimpleServiceRegistry registry, ServiceExport export) {
    return new FabricServer(
        registry,
        Greeter.class.getClassLoader(),
        id -> Optional.empty(),
        id -> Optional.empty(),
        Optional.empty(),
        owner -> owner.equals(OWNER) ? List.of(export) : List.of());
  }

  @Test
  @Timeout(10)
  void a_caller_bypassing_the_registry_and_dialing_directly_is_rejected_by_the_listeners_own_check()
      throws Exception {
    // The scenario GimleFabricAuthorizationException exists for: this test never goes through
    // FabricServiceRegistry's own caller-side filter at all -- it dials FabricServer directly,
    // exactly what a caller that obtained a ServiceEndpoint's raw address from the gossip catalog
    // could do. The listener's own re-check must still catch it.
    SimpleServiceRegistry registry = new SimpleServiceRegistry();
    registry.register(OWNER, Greeter.class, name -> "hello:" + name);
    ServiceExport export =
        new ServiceExport(
            Greeter.class.getName(), Version.parse("1.0.0"), Optional.of(Set.of("tenant-a")));

    server = serverWithExport(registry, export);
    InetSocketAddress address =
        (InetSocketAddress) server.listen(new InetSocketAddress("127.0.0.1", 0));

    FabricFrame response =
        FabricClient.call(address, invokeGreet("world", Optional.of("tenant-b")));

    assertInstanceOf(FabricFrame.InvokeError.class, response);
    Object thrown =
        ObjectMarshalling.deserialize(((FabricFrame.InvokeError) response).serializedThrowable());
    assertInstanceOf(GimleFabricAuthorizationException.class, thrown);
  }

  @Test
  @Timeout(10)
  void an_untenanted_caller_is_rejected_against_a_restricted_export() throws Exception {
    SimpleServiceRegistry registry = new SimpleServiceRegistry();
    registry.register(OWNER, Greeter.class, name -> "hello:" + name);
    ServiceExport export =
        new ServiceExport(
            Greeter.class.getName(), Version.parse("1.0.0"), Optional.of(Set.of("tenant-a")));

    server = serverWithExport(registry, export);
    InetSocketAddress address =
        (InetSocketAddress) server.listen(new InetSocketAddress("127.0.0.1", 0));

    FabricFrame response = FabricClient.call(address, invokeGreet("world", Optional.empty()));

    assertInstanceOf(FabricFrame.InvokeError.class, response);
  }

  @Test
  @Timeout(10)
  void a_caller_from_the_allowed_tenant_is_permitted_through_the_listeners_own_check()
      throws Exception {
    SimpleServiceRegistry registry = new SimpleServiceRegistry();
    registry.register(OWNER, Greeter.class, name -> "hello:" + name);
    ServiceExport export =
        new ServiceExport(
            Greeter.class.getName(), Version.parse("1.0.0"), Optional.of(Set.of("tenant-a")));

    server = serverWithExport(registry, export);
    InetSocketAddress address =
        (InetSocketAddress) server.listen(new InetSocketAddress("127.0.0.1", 0));

    FabricFrame response =
        FabricClient.call(address, invokeGreet("world", Optional.of("tenant-a")));

    assertInstanceOf(FabricFrame.InvokeResponse.class, response);
    assertEquals(
        "hello:world",
        ObjectMarshalling.deserialize(((FabricFrame.InvokeResponse) response).serializedReturn()));
  }

  @Test
  @Timeout(10)
  void an_export_with_no_tenant_restriction_permits_any_caller() throws Exception {
    SimpleServiceRegistry registry = new SimpleServiceRegistry();
    registry.register(OWNER, Greeter.class, name -> "hello:" + name);
    ServiceExport unrestricted = new ServiceExport(Greeter.class.getName(), Version.parse("1.0.0"));

    server = serverWithExport(registry, unrestricted);
    InetSocketAddress address =
        (InetSocketAddress) server.listen(new InetSocketAddress("127.0.0.1", 0));

    FabricFrame response =
        FabricClient.call(address, invokeGreet("world", Optional.of("any-tenant")));

    assertInstanceOf(FabricFrame.InvokeResponse.class, response);
  }
}
