package com.gimle.mimir.rpc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.core.authz.Account;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.parallel.Isolated;

/**
 * Regression coverage for a gray-failure store endpoint -- reachable (the TCP handshake completes)
 * but silent (it never writes a response) -- which used to block the calling thread forever because
 * neither {@link StoreConnection#connectionLocked} nor its read path set any timeout. Modeled on
 * {@link StoreClientClusterTest}'s real-loopback-TCP pattern, but with a hand-rolled "server" that
 * deliberately never answers instead of a real {@code StoreNode}.
 */
@Isolated
class StoreConnectionTimeoutTest {

  private final List<ServerSocket> servers = new ArrayList<>();
  private final List<Thread> acceptThreads = new ArrayList<>();
  private final List<Socket> acceptedSockets = new CopyOnWriteArrayList<>();
  private StoreConnection connection;
  private StoreClient client;

  @AfterEach
  void tearDown() {
    if (connection != null) {
      connection.close();
    }
    if (client != null) {
      client.close();
    }
    for (ServerSocket server : servers) {
      closeQuietly(server);
    }
    for (Socket socket : acceptedSockets) {
      closeQuietly(socket);
    }
    for (Thread thread : acceptThreads) {
      thread.interrupt();
    }
  }

  private static void closeQuietly(AutoCloseable closeable) {
    try {
      closeable.close();
    } catch (Exception ignored) {
      // best-effort cleanup
    }
  }

  /** Accepts every connection and then never writes a single byte back. */
  private ServerSocket startSilentServer() throws IOException {
    ServerSocket server = new ServerSocket();
    server.bind(new InetSocketAddress("127.0.0.1", 0));
    servers.add(server);
    acceptThreads.add(
        Thread.ofPlatform()
            .name("silent-store-endpoint")
            .start(
                () -> {
                  while (!server.isClosed()) {
                    try {
                      // Deliberately never read or write anything on the accepted socket -- it
                      // just sits open, exactly the "reachable but silent" gray failure this test
                      // proves StoreConnection no longer blocks on forever.
                      acceptedSockets.add(server.accept());
                    } catch (IOException e) {
                      return;
                    }
                  }
                }));
    return server;
  }

  /** Accepts a connection, decodes exactly one request, and answers with an empty account list. */
  private ServerSocket startRespondingServer() throws IOException {
    ServerSocket server = new ServerSocket();
    server.bind(new InetSocketAddress("127.0.0.1", 0));
    servers.add(server);
    acceptThreads.add(
        Thread.ofPlatform()
            .name("responding-store-endpoint")
            .start(
                () -> {
                  try (Socket accepted = server.accept()) {
                    acceptedSockets.add(accepted);
                    StoreCodec.read(accepted.getInputStream());
                    StoreCodec.write(
                        accepted.getOutputStream(), new StoreRpc.AccountListResult(List.of()));
                  } catch (IOException e) {
                    // server socket closed during teardown, or the client never connected
                    // (both benign for the test that doesn't exercise this endpoint)
                  }
                }));
    return server;
  }

  @Test
  @Timeout(20)
  void a_connection_that_accepts_but_never_responds_times_out_instead_of_blocking_forever()
      throws Exception {
    ServerSocket silentServer = startSilentServer();
    SocketAddress address = silentServer.getLocalSocketAddress();
    connection = new StoreConnection(address);

    long start = System.nanoTime();
    UncheckedIOException thrown =
        assertThrows(
            UncheckedIOException.class, () -> connection.call(new StoreRpc.ListAccounts()));
    long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

    assertTrue(thrown.getCause() instanceof SocketTimeoutException, "expected a read timeout");
    // Comfortably below the JUnit @Timeout ceiling above, proving the call returned on its own
    // rather than the test harness killing a still-blocked thread.
    assertTrue(
        elapsedMillis < 15_000, "call took " + elapsedMillis + "ms, expected a bounded timeout");
  }

  @Test
  @Timeout(20)
  void a_store_client_fails_over_past_a_silent_endpoint_to_one_that_answers() throws Exception {
    ServerSocket silentServer = startSilentServer();
    ServerSocket respondingServer = startRespondingServer();

    client =
        new StoreClient(
            List.of(
                silentServer.getLocalSocketAddress(), respondingServer.getLocalSocketAddress()));

    List<Account> accounts = client.listAccounts();

    assertEquals(List.of(), accounts);
  }
}
