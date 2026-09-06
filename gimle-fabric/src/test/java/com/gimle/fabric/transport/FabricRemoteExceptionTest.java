package com.gimle.fabric.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.core.module.ModuleId;
import com.gimle.core.module.ModuleInstanceId;
import com.gimle.core.module.Version;
import com.gimle.fabric.registry.Greeter;
import com.gimle.fabric.trace.TraceContext;
import com.gimle.module.lifecycle.SimpleServiceRegistry;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.net.InetSocketAddress;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceAccessMode;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

/**
 * What a caller learns when the far end of a fabric call throws. The invariant under test is that
 * an application failure never degrades into something a caller would read as the wire having
 * broken: whatever the target threw, the caller ends up with either that exception object itself or
 * its type name and message, and never a decode failure or a dropped connection.
 */
// Reads gimle.transport.protocol (through FabricClient/FabricServer) without ever setting it -- see
// FabricServerTest's own note.
@ResourceLock(value = Resources.SYSTEM_PROPERTIES, mode = ResourceAccessMode.READ)
class FabricRemoteExceptionTest {

  private static final AtomicLong CORRELATION_IDS = new AtomicLong();

  private static final ModuleInstanceId OWNER =
      ModuleInstanceId.unattached(
          new ModuleId("com.gimle.example.greeter", Version.parse("1.0.0")));
  private static final TraceContext TRACE = new TraceContext(1L, 2L, 3L, (byte) 1);

  private FabricServer server;

  @TempDir Path tempDir;

  @AfterEach
  void tearDown() {
    if (server != null) {
      server.close();
    }
  }

  /**
   * An exception carrying a live handle no serializer can write -- the shape a real service
   * exception takes when it captures something from its own runtime rather than plain data.
   */
  private static final class LiveHandleFailure extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final Object handle;

    LiveHandleFailure(String message, Object handle) {
      super(message);
      this.handle = handle;
    }

    Object handle() {
      return handle;
    }
  }

  private InetSocketAddress startServerThrowing(RuntimeException failure) throws IOException {
    SimpleServiceRegistry registry = new SimpleServiceRegistry();
    registry.register(
        OWNER,
        Greeter.class,
        name -> {
          throw failure;
        });
    server = new FabricServer(registry, Greeter.class.getClassLoader());
    return (InetSocketAddress) server.listen(new InetSocketAddress("127.0.0.1", 0));
  }

  private static FabricFrame.InvokeRequest invokeGreet() {
    return new FabricFrame.InvokeRequest(
        CORRELATION_IDS.incrementAndGet(),
        TRACE,
        Greeter.class.getName(),
        "greet",
        new String[] {"java.lang.String"},
        ObjectMarshalling.serialize(new Object[] {"world"}));
  }

  /**
   * Compiles and loads an exception class the running test's own classloader cannot see, standing
   * in for the common real case: a provider module defines its service contract's exception types
   * inside its own layer, and the calling module has no copy of them. The loader's parent is the
   * platform loader specifically so the class is reachable from nowhere on the application
   * classpath, which is what makes the caller's resolution genuinely fail.
   */
  private Class<? extends RuntimeException> compileUnloadableExceptionClass() throws Exception {
    Path source = tempDir.resolve("ModulePrivateFailure.java");
    Files.writeString(
        source,
        """
        package com.gimle.fabric.provideronly;

        public class ModulePrivateFailure extends RuntimeException {
          private static final long serialVersionUID = 1L;

          public ModulePrivateFailure(String message) {
            super(message);
          }
        }
        """);
    Path classes = Files.createDirectory(tempDir.resolve("classes"));
    JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
    assertNotNull(compiler, "a JDK (not a JRE) is required to run this test");
    assertEquals(
        0,
        compiler.run(
            null, null, null, "-d", classes.toString(), source.toAbsolutePath().toString()));
    URLClassLoader isolated =
        new URLClassLoader(
            new URL[] {classes.toUri().toURL()}, ClassLoader.getPlatformClassLoader());
    return isolated
        .loadClass("com.gimle.fabric.provideronly.ModulePrivateFailure")
        .asSubclass(RuntimeException.class);
  }

  @Test
  @Timeout(15)
  void an_exception_the_caller_can_load_still_arrives_as_its_own_type() throws Exception {
    InetSocketAddress address =
        startServerThrowing(new IllegalArgumentException("unknown customer"));

    FabricFrame response = FabricClient.call(address, invokeGreet());

    FabricFrame.InvokeError error = assertInstanceOf(FabricFrame.InvokeError.class, response);
    Throwable thrown = error.toThrowable(getClass().getClassLoader());
    assertInstanceOf(IllegalArgumentException.class, thrown);
    assertEquals("unknown customer", thrown.getMessage());
  }

  @Test
  @Timeout(15)
  void an_exception_type_the_caller_cannot_load_arrives_named_rather_than_as_a_decode_failure()
      throws Exception {
    Class<? extends RuntimeException> unloadable = compileUnloadableExceptionClass();
    Constructor<? extends RuntimeException> constructor = unloadable.getConstructor(String.class);
    InetSocketAddress address = startServerThrowing(constructor.newInstance("unknown customer"));

    FabricFrame response = FabricClient.call(address, invokeGreet());

    FabricFrame.InvokeError error = assertInstanceOf(FabricFrame.InvokeError.class, response);
    assertEquals("com.gimle.fabric.provideronly.ModulePrivateFailure", error.remoteTypeName());
    assertEquals(Optional.of("unknown customer"), error.remoteMessage());
    RemoteInvocationException thrown =
        assertInstanceOf(
            RemoteInvocationException.class, error.toThrowable(getClass().getClassLoader()));
    assertEquals("com.gimle.fabric.provideronly.ModulePrivateFailure", thrown.remoteTypeName());
    assertEquals(Optional.of("unknown customer"), thrown.remoteMessage());
    assertTrue(thrown.getMessage().contains("ModulePrivateFailure"));
    assertTrue(thrown.getMessage().contains("unknown customer"));
  }

  @Test
  @Timeout(15)
  void a_target_exception_that_cannot_be_serialized_still_produces_an_answer() throws Exception {
    LiveHandleFailure failure = new LiveHandleFailure("unknown customer", new Object());
    assertNotNull(failure.handle());
    InetSocketAddress address = startServerThrowing(failure);

    FabricFrame response = FabricClient.call(address, invokeGreet());

    FabricFrame.InvokeError error = assertInstanceOf(FabricFrame.InvokeError.class, response);
    assertEquals(0, error.serializedThrowable().length);
    assertEquals(LiveHandleFailure.class.getName(), error.remoteTypeName());
    assertEquals(Optional.of("unknown customer"), error.remoteMessage());
    RemoteInvocationException thrown =
        assertInstanceOf(
            RemoteInvocationException.class, error.toThrowable(getClass().getClassLoader()));
    assertTrue(thrown.getMessage().contains("unknown customer"));
  }

  @Test
  @Timeout(15)
  void an_exception_with_a_null_message_reports_the_absence_rather_than_an_empty_message()
      throws Exception {
    InetSocketAddress address = startServerThrowing(new IllegalStateException());

    FabricFrame response = FabricClient.call(address, invokeGreet());

    FabricFrame.InvokeError error = assertInstanceOf(FabricFrame.InvokeError.class, response);
    assertEquals(IllegalStateException.class.getName(), error.remoteTypeName());
    assertEquals(Optional.empty(), error.remoteMessage());
    // The object itself still crosses here, so the caller keeps the real type; the null message
    // must survive as a null, never as "".
    Throwable thrown = error.toThrowable(getClass().getClassLoader());
    assertInstanceOf(IllegalStateException.class, thrown);
    assertEquals(null, thrown.getMessage());
  }

  @Test
  void a_null_message_on_an_unloadable_type_is_reported_as_no_message_at_all() {
    RemoteInvocationException thrown =
        assertInstanceOf(
            RemoteInvocationException.class,
            new FabricFrame.InvokeError(
                    7L, "com.example.Vanished", Optional.empty(), new byte[0], 0)
                .toThrowable(getClass().getClassLoader()));

    assertEquals(Optional.empty(), thrown.remoteMessage());
    assertTrue(thrown.getMessage().contains("com.example.Vanished"));
    assertTrue(thrown.getMessage().contains("(no message)"));
  }

  @Test
  void an_undecodable_error_payload_falls_back_to_the_frames_own_type_name_and_message() {
    FabricFrame.InvokeError error =
        new FabricFrame.InvokeError(
            7L, "com.example.Broken", Optional.of("unknown customer"), new byte[] {1, 2, 3}, 0);

    RemoteInvocationException thrown =
        assertInstanceOf(
            RemoteInvocationException.class, error.toThrowable(getClass().getClassLoader()));

    assertEquals("com.example.Broken", thrown.remoteTypeName());
    assertEquals(Optional.of("unknown customer"), thrown.remoteMessage());
  }

  @Test
  void a_serialized_payload_that_is_not_a_throwable_falls_back_to_the_named_remote_failure() {
    FabricFrame.InvokeError error =
        new FabricFrame.InvokeError(
            7L,
            "com.example.Broken",
            Optional.of("unknown customer"),
            ObjectMarshalling.serialize(List.of("not a throwable")),
            0);

    assertInstanceOf(
        RemoteInvocationException.class, error.toThrowable(getClass().getClassLoader()));
  }
}
