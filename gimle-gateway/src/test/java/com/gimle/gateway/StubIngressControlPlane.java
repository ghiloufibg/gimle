package com.gimle.gateway;

import com.sun.net.httpserver.HttpServer;
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
 */
final class StubIngressControlPlane implements AutoCloseable {

  private final HttpServer server;
  private final AtomicReference<List<String>> fabricPaths = new AtomicReference<>(List.of());

  StubIngressControlPlane(List<String> initialFabricPaths) {
    fabricPaths.set(List.copyOf(initialFabricPaths));
    try {
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
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

  String endpoint() {
    return "127.0.0.1:" + server.getAddress().getPort();
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
