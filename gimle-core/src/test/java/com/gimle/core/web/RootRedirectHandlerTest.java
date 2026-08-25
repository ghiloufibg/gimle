package com.gimle.core.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpClient.Redirect;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Exercises {@link RootRedirectHandler} directly over a real loopback HTTP connection. */
class RootRedirectHandlerTest {

  private HttpServer server;
  private HttpClient client;
  private String baseUrl;

  @BeforeEach
  void startServer() throws IOException {
    server = HttpServer.create(new InetSocketAddress(0), 0);
    server.createContext("/", new RootRedirectHandler("/console"));
    server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
    server.start();
    baseUrl = "http://localhost:" + server.getAddress().getPort();
    // Redirect.NEVER so a 302 comes back as itself rather than being followed transparently.
    client = HttpClient.newBuilder().followRedirects(Redirect.NEVER).build();
  }

  @AfterEach
  void stopServer() {
    server.stop(0);
  }

  private HttpResponse<String> get(String path) throws Exception {
    return client.send(
        HttpRequest.newBuilder(URI.create(baseUrl + path)).GET().build(),
        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
  }

  @Test
  void redirects_the_bare_root_path_to_the_target() throws Exception {
    HttpResponse<String> response = get("/");

    assertEquals(302, response.statusCode());
    assertEquals("/console", response.headers().firstValue("Location").orElse(""));
  }

  @Test
  void does_not_redirect_an_unrelated_unmatched_path() throws Exception {
    HttpResponse<String> response = get("/some-typo-nobody-registered");

    assertEquals(404, response.statusCode());
    assertTrue(response.headers().firstValue("Location").isEmpty());
  }
}
