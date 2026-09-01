package com.gimle.fabric.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.gimle.core.module.ModuleId;
import com.gimle.core.module.Version;
import com.gimle.fabric.registry.Greeter;
import com.gimle.fabric.trace.TraceContext;
import com.gimle.module.lifecycle.SimpleServiceRegistry;
import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * The listener half of the retry story, exercised over a real socket rather than against {@link
 * InvocationDeduplicator} directly: a retry always arrives on a brand-new connection (the client
 * opens one per attempt), so suppression has to be a property of the listener, not of a connection.
 */
class FabricServerDeduplicationTest {

  private static final ModuleId OWNER =
      new ModuleId("com.gimle.example.greeter", Version.parse("1.0.0"));
  private static final TraceContext TRACE = new TraceContext(1L, 2L, 3L, (byte) 1);

  private FabricServer server;

  @AfterEach
  void tearDown() {
    if (server != null) {
      server.close();
    }
  }

  private static FabricFrame.InvokeRequest invokeGreet(long correlationId, String arg) {
    return new FabricFrame.InvokeRequest(
        correlationId,
        TRACE,
        Greeter.class.getName(),
        "greet",
        new String[] {"java.lang.String"},
        ObjectMarshalling.serialize(new Object[] {arg}));
  }

  private InetSocketAddress startCounting(AtomicInteger invocations) throws Exception {
    SimpleServiceRegistry registry = new SimpleServiceRegistry();
    registry.register(
        OWNER, Greeter.class, name -> "hello:" + name + ":" + invocations.incrementAndGet());
    server = new FabricServer(registry, Greeter.class.getClassLoader());
    return (InetSocketAddress) server.listen(new InetSocketAddress("127.0.0.1", 0));
  }

  @Test
  @Timeout(10)
  void a_retry_carrying_the_same_correlation_id_is_answered_without_re_invoking_the_target()
      throws Exception {
    AtomicInteger invocations = new AtomicInteger();
    InetSocketAddress address = startCounting(invocations);

    FabricFrame first = FabricClient.call(address, invokeGreet(42L, "world"));
    FabricFrame retry = FabricClient.call(address, invokeGreet(42L, "world"));

    assertEquals(1, invocations.get(), "the target method must have run exactly once");
    assertEquals("hello:world:1", returned(first));
    assertEquals("hello:world:1", returned(retry), "the retry sees the original answer");
  }

  @Test
  @Timeout(10)
  void two_genuinely_different_calls_are_both_served() throws Exception {
    AtomicInteger invocations = new AtomicInteger();
    InetSocketAddress address = startCounting(invocations);

    assertEquals("hello:a:1", returned(FabricClient.call(address, invokeGreet(1L, "a"))));
    assertEquals("hello:b:2", returned(FabricClient.call(address, invokeGreet(2L, "b"))));
    assertEquals(2, invocations.get());
  }

  /**
   * An error answer is an answer: a target that threw has already run, so replaying its exception
   * is exactly as important as replaying a successful return -- retrying into a second execution
   * would be the very duplication this suppresses.
   */
  @Test
  @Timeout(10)
  void a_retry_of_a_request_whose_target_threw_replays_the_error_rather_than_re_running_it()
      throws Exception {
    AtomicInteger invocations = new AtomicInteger();
    SimpleServiceRegistry registry = new SimpleServiceRegistry();
    registry.register(
        OWNER,
        Greeter.class,
        name -> {
          invocations.incrementAndGet();
          throw new IllegalStateException("boom");
        });
    server = new FabricServer(registry, Greeter.class.getClassLoader());
    InetSocketAddress address =
        (InetSocketAddress) server.listen(new InetSocketAddress("127.0.0.1", 0));

    FabricFrame first = FabricClient.call(address, invokeGreet(99L, "world"));
    FabricFrame retry = FabricClient.call(address, invokeGreet(99L, "world"));

    assertEquals(1, invocations.get());
    assertEquals(FabricFrame.InvokeError.class, first.getClass());
    assertEquals(FabricFrame.InvokeError.class, retry.getClass());
  }

  private static String returned(FabricFrame frame) {
    return (String)
        ObjectMarshalling.deserialize(
            ((FabricFrame.InvokeResponse) frame).serializedReturn(),
            FabricServerDeduplicationTest.class.getClassLoader());
  }
}
