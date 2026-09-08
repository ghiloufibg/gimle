package com.gimle.gateway;

import com.gimle.core.tls.SslContexts;
import com.gimle.core.tls.TlsSettings;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * A control plane that serves nothing but {@code GET /ingresses}, which is the only thing a gateway
 * asks one for. Its declared routes are swappable while it runs, so a test can drive a live
 * route-table reload the same way an operator submitting a new Ingress does.
 *
 * <p>Plaintext by default; {@link #StubIngressControlPlane(List, TlsSettings)} terminates TLS
 * instead, using a leaf certificate the caller signs against the same cluster CA the gateway under
 * test trusts -- needed for a {@code gimle.transport.protocol=tls} test, where the gateway's own
 * outbound {@code HttpClient} now refuses to speak plaintext to this stub.
 */
final class StubIngressControlPlane implements AutoCloseable {

  private final HttpServer server;
  private final AtomicReference<List<String>> fabricPaths = new AtomicReference<>(List.of());

  StubIngressControlPlane(List<String> initialFabricPaths) {
    this(initialFabricPaths, null);
  }

  StubIngressControlPlane(List<String> initialFabricPaths, TlsSettings serverTls) {
    fabricPaths.set(List.copyOf(initialFabricPaths));
    try {
      server = createServer(serverTls);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    server.createContext(
        "/ingresses",
        exchange -> {
          byte[] body = json().getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    server.start();
  }

  private static HttpServer createServer(TlsSettings serverTls) throws IOException {
    if (serverTls == null) {
      return HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    }
    HttpsServer httpsServer = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    httpsServer.setHttpsConfigurator(new HttpsConfigurator(SslContexts.forMutualTls(serverTls)));
    return httpsServer;
  }

  /**
   * "localhost", never "127.0.0.1" -- a TLS-mode caller's leaf certificate only ever names
   * "localhost" as a Subject Alternative Name (see {@code GatewayHooksTlsTest}'s own inbound-TLS
   * assertions), so a caller doing real hostname verification against a literal IP would fail the
   * handshake before its request was ever sent.
   */
  String endpoint() {
    return "localhost:" + server.getAddress().getPort();
  }

  void declare(List<String> newFabricPaths) {
    fabricPaths.set(List.copyOf(newFabricPaths));
  }

  @Override
  public void close() {
    server.stop(0);
  }

  private String json() {
    String routes =
        fabricPaths.get().stream()
            .map(
                path ->
                    "{\"kind\":\"FABRIC\",\"path\":\""
                        + path
                        + "\",\"prefix\":false,\"interfaceName\":\""
                        + TestGreeter.class.getName()
                        + "\",\"majorVersion\":1,\"methodName\":\"greet\","
                        + "\"paramType\":\"STRING\"}")
            .collect(Collectors.joining(","));
    return "[{\"name\":\"greeter\",\"tenantId\":\"default\",\"routes\":[" + routes + "]}]";
  }
}
