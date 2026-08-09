package com.gimle.fabric.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.core.module.ModuleId;
import com.gimle.core.module.Version;
import com.gimle.fabric.registry.Greeter;
import com.gimle.fabric.trace.TraceContext;
import com.gimle.module.lifecycle.ModuleContext;
import com.gimle.module.lifecycle.SimpleModuleContext;
import com.gimle.module.lifecycle.SimpleServiceRegistry;
import com.gimle.observability.WorkerMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.opentelemetry.api.baggage.Baggage;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Optional;
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

  @Test
  @Timeout(10)
  void baggage_from_the_caller_survives_an_inbound_call_into_the_handler() throws Exception {
    // P2-10: baggage isn't just decoded and discarded -- it must actually be the ambient
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

    // Bounded to 1: at any instant while all three client calls are outstanding, only one should
    // ever be inside the service method -- give the (deliberately unbounded, if the fix
    // regresses) alternative a real chance to show itself before asserting.
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
}
