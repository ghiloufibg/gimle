package com.gimle.hilmir.analyze;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.hilmir.analyze.testsupport.HilmirTestJarBuilder;
import org.junit.jupiter.api.Test;

class BytecodeScannerTest {

  @Test
  void a_clean_class_produces_no_hazards() {
    ClassHazards hazards =
        scan(
            "com.gimle.hilmir.analyze.fixture.Clean",
            """
            package com.gimle.hilmir.analyze.fixture;
            public class Clean {
              public Clean() {}
              public int add(int a, int b) { return a + b; }
            }
            """);
    assertTrue(hazards.isClean());
  }

  @Test
  void detects_system_exit() {
    ClassHazards hazards =
        scan(
            "com.gimle.hilmir.analyze.fixture.CallsExit",
            """
            package com.gimle.hilmir.analyze.fixture;
            public class CallsExit {
              void boom() { System.exit(1); }
            }
            """);
    assertTrue(hazards.callsSystemExit());
    assertFalse(hazards.registersShutdownHook());
  }

  @Test
  void detects_shutdown_hook_registration() {
    ClassHazards hazards =
        scan(
            "com.gimle.hilmir.analyze.fixture.RegistersHook",
            """
            package com.gimle.hilmir.analyze.fixture;
            public class RegistersHook {
              void install() {
                Runtime.getRuntime().addShutdownHook(new Thread(() -> {}));
              }
            }
            """);
    assertTrue(hazards.registersShutdownHook());
  }

  @Test
  void detects_non_daemon_thread_construction() {
    ClassHazards hazards =
        scan(
            "com.gimle.hilmir.analyze.fixture.SpawnsThread",
            """
            package com.gimle.hilmir.analyze.fixture;
            public class SpawnsThread {
              void go() {
                Thread t = new Thread(() -> {});
                t.start();
              }
            }
            """);
    assertTrue(hazards.constructsNonDaemonThread());
  }

  @Test
  void a_daemon_thread_with_set_daemon_is_not_flagged() {
    ClassHazards hazards =
        scan(
            "com.gimle.hilmir.analyze.fixture.SpawnsDaemonThread",
            """
            package com.gimle.hilmir.analyze.fixture;
            public class SpawnsDaemonThread {
              void go() {
                Thread t = new Thread(() -> {});
                t.setDaemon(true);
                t.start();
              }
            }
            """);
    assertFalse(hazards.constructsNonDaemonThread());
  }

  @Test
  void detects_native_library_loading() {
    ClassHazards hazards =
        scan(
            "com.gimle.hilmir.analyze.fixture.LoadsNative",
            """
            package com.gimle.hilmir.analyze.fixture;
            public class LoadsNative {
              static { System.loadLibrary("foo"); }
            }
            """);
    assertTrue(hazards.loadsNativeLibrary());
  }

  @Test
  void detects_server_socket_opening() {
    ClassHazards hazards =
        scan(
            "com.gimle.hilmir.analyze.fixture.OpensSocket",
            """
            package com.gimle.hilmir.analyze.fixture;
            import java.net.ServerSocket;
            public class OpensSocket {
              void go() throws Exception {
                new ServerSocket(0);
              }
            }
            """);
    assertTrue(hazards.opensServerSocket());
  }

  @Test
  void detects_connecting_socket_construction() {
    ClassHazards hazards =
        scan(
            "com.gimle.hilmir.analyze.fixture.OpensOutboundSocket",
            """
            package com.gimle.hilmir.analyze.fixture;
            import java.net.Socket;
            public class OpensOutboundSocket {
              void go() throws Exception {
                new Socket("example.invalid", 80);
              }
            }
            """);
    assertTrue(hazards.makesOutboundConnection());
  }

  @Test
  void detects_bare_connect_call_after_no_arg_socket_construction() {
    ClassHazards hazards =
        scan(
            "com.gimle.hilmir.analyze.fixture.ConnectsSocketSeparately",
            """
            package com.gimle.hilmir.analyze.fixture;
            import java.net.InetSocketAddress;
            import java.net.Socket;
            public class ConnectsSocketSeparately {
              void go() throws Exception {
                Socket socket = new Socket();
                socket.connect(new InetSocketAddress("example.invalid", 80));
              }
            }
            """);
    assertTrue(hazards.makesOutboundConnection());
  }

  @Test
  void a_bare_no_arg_socket_construction_with_no_connect_call_is_not_flagged() {
    ClassHazards hazards =
        scan(
            "com.gimle.hilmir.analyze.fixture.ConstructsSocketOnly",
            """
            package com.gimle.hilmir.analyze.fixture;
            import java.net.Socket;
            public class ConstructsSocketOnly {
              Socket socket() { return new Socket(); }
            }
            """);
    assertFalse(hazards.makesOutboundConnection());
  }

  @Test
  void detects_socket_channel_open() {
    ClassHazards hazards =
        scan(
            "com.gimle.hilmir.analyze.fixture.OpensSocketChannel",
            """
            package com.gimle.hilmir.analyze.fixture;
            import java.nio.channels.SocketChannel;
            public class OpensSocketChannel {
              void go() throws Exception {
                SocketChannel.open();
              }
            }
            """);
    assertTrue(hazards.makesOutboundConnection());
  }

  @Test
  void detects_http_client_construction_via_static_factory() {
    ClassHazards hazards =
        scan(
            "com.gimle.hilmir.analyze.fixture.BuildsHttpClientViaFactory",
            """
            package com.gimle.hilmir.analyze.fixture;
            import java.net.http.HttpClient;
            public class BuildsHttpClientViaFactory {
              void go() {
                HttpClient.newHttpClient();
              }
            }
            """);
    assertTrue(hazards.makesOutboundConnection());
  }

  @Test
  void detects_http_client_construction_via_builder() {
    ClassHazards hazards =
        scan(
            "com.gimle.hilmir.analyze.fixture.BuildsHttpClientViaBuilder",
            """
            package com.gimle.hilmir.analyze.fixture;
            import java.net.http.HttpClient;
            public class BuildsHttpClientViaBuilder {
              void go() {
                HttpClient.newBuilder().build();
              }
            }
            """);
    assertTrue(hazards.makesOutboundConnection());
  }

  @Test
  void detects_static_executor_with_no_shutdown_call() {
    ClassHazards hazards =
        scan(
            "com.gimle.hilmir.analyze.fixture.LeakyExecutor",
            """
            package com.gimle.hilmir.analyze.fixture;
            import java.util.concurrent.ExecutorService;
            import java.util.concurrent.Executors;
            public class LeakyExecutor {
              static final ExecutorService POOL = Executors.newFixedThreadPool(2);
            }
            """);
    assertTrue(hazards.hasUnshutdownStaticExecutor());
  }

  @Test
  void a_static_executor_that_is_shut_down_is_not_flagged() {
    ClassHazards hazards =
        scan(
            "com.gimle.hilmir.analyze.fixture.CleanExecutor",
            """
            package com.gimle.hilmir.analyze.fixture;
            import java.util.concurrent.ExecutorService;
            import java.util.concurrent.Executors;
            public class CleanExecutor {
              static final ExecutorService POOL = Executors.newFixedThreadPool(2);
              void stop() { POOL.shutdown(); }
            }
            """);
    assertFalse(hazards.hasUnshutdownStaticExecutor());
  }

  @Test
  void detects_declared_interfaces_and_no_arg_constructor() {
    ClassHazards hazards =
        scan(
            "com.gimle.hilmir.analyze.fixture.Probe",
            """
            package com.gimle.hilmir.analyze.fixture;
            public class Probe implements java.io.Serializable {
              public Probe() {}
            }
            """);
    assertTrue(hazards.declaredInterfaces().contains("java.io.Serializable"));
    assertTrue(hazards.hasNoArgConstructor());
  }

  @Test
  void a_class_with_only_an_arg_taking_constructor_has_no_no_arg_constructor() {
    ClassHazards hazards =
        scan(
            "com.gimle.hilmir.analyze.fixture.NoDefaultCtor",
            """
            package com.gimle.hilmir.analyze.fixture;
            public class NoDefaultCtor {
              private final int x;
              public NoDefaultCtor(int x) { this.x = x; }
            }
            """);
    assertFalse(hazards.hasNoArgConstructor());
  }

  private static ClassHazards scan(String binaryName, String source) {
    byte[] bytes = HilmirTestJarBuilder.compileClass(binaryName, source);
    return BytecodeScanner.scan(bytes);
  }
}
