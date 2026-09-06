package com.gimle.controlplane.andvari;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.core.module.ModuleId;
import com.gimle.core.module.Version;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * {@link AndvariClient}'s multi-endpoint rotation/failover: with more than one {@code host:port}
 * configured, a real HTTP round trip against an unreachable first endpoint falls through to a
 * reachable second one instead of failing outright -- the same posture {@code StoreClient#sendRead}
 * already proves for {@code gimle-mimir}'s own reads, exercised here for Andvari's leaderless "any
 * replica can answer" equivalent.
 */
class AndvariClientTest {

  private HttpServer reachable;

  @AfterEach
  void tearDown() {
    if (reachable != null) {
      reachable.stop(0);
    }
  }

  @Test
  @Timeout(10)
  void a_head_call_fails_over_from_an_unreachable_endpoint_to_a_reachable_one() throws Exception {
    int deadPort = closedLoopbackPort();
    reachable = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    reachable.createContext(
        "/artifacts/com.example.app/1.0.0",
        exchange -> {
          exchange.getResponseHeaders().add("X-Gimle-Artifact-Sha256", "deadbeef");
          exchange.sendResponseHeaders(200, -1);
          exchange.close();
        });
    reachable.start();

    try (AndvariClient client =
        new AndvariClient(
            List.of("127.0.0.1:" + deadPort, "127.0.0.1:" + reachable.getAddress().getPort()),
            Optional.empty())) {
      AndvariClient.HeadOutcome outcome =
          client.head(new ModuleId("com.example.app", Version.parse("1.0.0")));

      AndvariClient.HeadOutcome.Found found =
          assertInstanceOf(AndvariClient.HeadOutcome.Found.class, outcome);
      assertEquals("deadbeef", found.sha256());
    }
  }

  @Test
  @Timeout(10)
  void unreachable_on_every_configured_endpoint_answers_unreachable() throws Exception {
    int deadPortA = closedLoopbackPort();
    int deadPortB = closedLoopbackPort();

    try (AndvariClient client =
        new AndvariClient(
            List.of("127.0.0.1:" + deadPortA, "127.0.0.1:" + deadPortB), Optional.empty())) {
      AndvariClient.HeadOutcome outcome =
          client.head(new ModuleId("com.example.app", Version.parse("1.0.0")));

      assertInstanceOf(AndvariClient.HeadOutcome.Unreachable.class, outcome);
    }
  }

  @Test
  @Timeout(30)
  void an_unresolvable_registry_host_is_reported_with_its_real_cause() throws Exception {
    // The .invalid TLD is reserved as never-resolvable, so this fails at name resolution rather
    // than at connect -- the failure the JDK HTTP client reports as a ConnectException carrying no
    // message of its own, which a bare getMessage() renders as the literal text "null".
    try (AndvariClient client =
        new AndvariClient(List.of("andvari-registry.invalid:9"), Optional.empty())) {
      AndvariClient.HeadOutcome.Unreachable unreachable =
          assertInstanceOf(
              AndvariClient.HeadOutcome.Unreachable.class,
              client.head(new ModuleId("com.example.app", Version.parse("1.0.0"))));

      assertFalse(
          unreachable.reason().endsWith("null"),
          "an unreachable registry must not report its cause as the literal text null: "
              + unreachable.reason());
      assertTrue(
          unreachable.reason().contains("Exception"),
          "the reason should name the failure that made the registry unreachable: "
              + unreachable.reason());
    }
  }

  /** A loopback port nothing is listening on -- bound then immediately released. */
  private static int closedLoopbackPort() throws IOException {
    try (ServerSocket socket = new ServerSocket(0)) {
      return socket.getLocalPort();
    }
  }
}
