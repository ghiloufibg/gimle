package com.gimle.fafnir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.core.authz.Account;
import com.gimle.core.authz.PasswordHashes;
import com.gimle.core.protocol.Json;
import com.gimle.fafnir.testsupport.InProcessStore;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

/**
 * Fafnir's own console session story ({@code /auth/login}, {@code /auth/session}, {@code
 * /auth/logout}) and {@code /status} -- deliberately exercised without TLS/client certificates:
 * unlike {@code authorizeSecrets}'s {@code /secrets/*} gate, none of these four endpoints requires
 * an {@code HttpsExchange} to function (see {@code FafnirServer#handleAuthLogin}'s own javadoc for
 * why), so this suite proves the cookie mechanics directly rather than through TLS scaffolding
 * {@code FafnirServerTlsTest} already covers elsewhere.
 */
@ResourceLock(Resources.SYSTEM_PROPERTIES)
@ResourceLock("gimle-fafnir-server-http")
class FafnirServerAuthTest {

  @TempDir Path tempDir;

  private InProcessStore store;
  private FafnirServer server;
  private final HttpClient client = HttpClient.newHttpClient();
  private String baseUrl;

  @BeforeEach
  void setUp() throws Exception {
    store = InProcessStore.start(tempDir.resolve("store"));
    FafnirCrypto crypto = new FafnirCrypto(store.client(), tempDir.resolve("keys/secret.key"));
    server = new FafnirServer(crypto, 0);
    server.start();
    baseUrl = "http://127.0.0.1:" + server.port();
  }

  @AfterEach
  void tearDown() {
    server.close();
    store.close();
  }

  private void seedAccount(String username, String password) {
    store.store().putAccount(new Account(username, PasswordHashes.hash(password.toCharArray())));
  }

  private HttpResponse<String> post(String path, Map<String, Object> body) throws Exception {
    return client.send(
        HttpRequest.newBuilder(URI.create(baseUrl + path))
            .POST(HttpRequest.BodyPublishers.ofString(Json.write(body)))
            .build(),
        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
  }

  private HttpResponse<String> getWithCookie(String path, String cookie) throws Exception {
    HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path)).GET();
    if (cookie != null) {
      builder.header("Cookie", cookie);
    }
    return client.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
  }

  @Test
  @Timeout(10)
  void login_session_and_logout_round_trip_with_no_client_certificate_at_all() throws Exception {
    seedAccount("admin", "s3cret-password");

    // 1. No cookie yet: /auth/session is unauthenticated.
    assertEquals(401, getWithCookie("/auth/session", null).statusCode());

    // 2. A correct login sets a session cookie.
    HttpResponse<String> login =
        post("/auth/login", Map.of("username", "admin", "password", "s3cret-password"));
    assertEquals(200, login.statusCode());
    String setCookie = login.headers().firstValue("Set-Cookie").orElse("");
    assertTrue(setCookie.contains("gimle_fafnir_session="), "expected Fafnir's own cookie name");
    String cookie = setCookie.substring(0, setCookie.indexOf(';'));

    // 3. That cookie alone (no client certificate) authorizes /auth/session afterward.
    HttpResponse<String> session = getWithCookie("/auth/session", cookie);
    assertEquals(200, session.statusCode());
    assertEquals("admin", Json.asObject(Json.parse(session.body())).get("username"));

    // 4. Logout tells the browser to drop the cookie (Max-Age=0).
    HttpResponse<String> logout =
        client.send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/auth/logout"))
                .POST(HttpRequest.BodyPublishers.noBody())
                .header("Cookie", cookie)
                .build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    assertEquals(200, logout.statusCode());
    assertTrue(logout.headers().firstValue("Set-Cookie").orElse("").contains("Max-Age=0"));
  }

  @Test
  @Timeout(10)
  void a_wrong_password_is_rejected_with_no_cookie_set() throws Exception {
    seedAccount("admin", "s3cret-password");

    HttpResponse<String> login =
        post("/auth/login", Map.of("username", "admin", "password", "wrong-password"));

    assertEquals(401, login.statusCode());
    assertTrue(login.headers().firstValue("Set-Cookie").isEmpty());
  }

  @Test
  @Timeout(10)
  void an_unknown_username_is_rejected_the_same_way_as_a_wrong_password() throws Exception {
    HttpResponse<String> login =
        post("/auth/login", Map.of("username", "nobody", "password", "anything"));

    assertEquals(401, login.statusCode());
  }

  @Test
  @Timeout(10)
  void status_reports_uptime_active_key_and_transport_mode() throws Exception {
    HttpResponse<String> response =
        client.send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/status")).GET().build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

    assertEquals(200, response.statusCode());
    Map<String, Object> body = Json.asObject(Json.parse(response.body()));
    assertTrue(((Number) body.get("uptimeSeconds")).longValue() >= 0);
    assertEquals(0, ((Number) body.get("activeKeyId")).intValue());
    assertEquals("PLAINTEXT", body.get("transportProtocol"));
    assertEquals(List.of(), body.get("tenants"));
  }
}
