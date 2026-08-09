package com.gimle.fafnir;

import com.gimle.core.protocol.Json;
import com.gimle.core.tls.SslContexts;
import com.gimle.core.tls.TlsSettings;
import com.gimle.core.tls.TransportProtocol;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsParameters;
import com.sun.net.httpserver.HttpsServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Fafnir's HTTP surface: {@code com.sun.net.httpserver.HttpServer}, JDK-bundled, no framework
 * dependency -- the same minimal stack {@code ApiServer} already uses, for the same reason
 * (CLAUDE.md's explicit non-goal of pulling in Spring/Netty/Quarkus for something this small).
 *
 * <p>Phase A scope only: the internal crypto operations {@code gimle-controlplane}'s {@code
 * ApiServer} needs to keep its existing {@code /config/*} behavior working with crypto now living
 * out-of-process ({@code /internal/secrets/encrypt}, {@code /internal/secrets/decrypt}) plus {@code
 * /secrets/rotate-key}, moved here verbatim from {@code ApiServer.rotateSecretsKey}. The public,
 * versioned {@code /secrets/{tenantId}/...} surface (design doc §6e/§7) is a later addition on top
 * of this same server, not a different process.
 */
public final class FafnirServer implements AutoCloseable {

  private static final Logger log = LoggerFactory.getLogger(FafnirServer.class);

  private final FafnirCrypto crypto;
  // Not final: a TLS rotation rebuilds this the same way ApiServer's own #server field does --
  // see that class's field javadoc for why a rebuild, not a hot-swap, is the only supported path.
  private volatile HttpServer server;
  private final int boundPort;

  public FafnirServer(FafnirCrypto crypto, int port) throws IOException {
    this.crypto = crypto;
    this.server = createHttpServer(port);
    this.boundPort = server.getAddress().getPort();
    registerContexts(server);
    server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
  }

  private static HttpServer createHttpServer(int port) throws IOException {
    if (TransportProtocol.fromConfig() == TransportProtocol.PLAINTEXT) {
      return HttpServer.create(new InetSocketAddress(port), 0);
    }
    SSLContext sslContext = SslContexts.forMutualTls(TlsSettings.fromConfig());
    HttpsServer httpsServer = HttpsServer.create(new InetSocketAddress(port), 0);
    httpsServer.setHttpsConfigurator(
        new HttpsConfigurator(sslContext) {
          @Override
          public void configure(HttpsParameters params) {
            // Same ordering requirement as ApiServer's own createHttpServer: setSSLParameters(...)
            // copies its argument's wantClientAuth onto params, so this must run through that
            // call, not params.setWantClientAuth(...) directly.
            SSLParameters sslParameters = getSSLContext().getDefaultSSLParameters();
            sslParameters.setWantClientAuth(true);
            params.setSSLParameters(sslParameters);
          }
        });
    return httpsServer;
  }

  private void registerContexts(HttpServer target) {
    target.createContext("/internal/secrets/encrypt", this::handleEncrypt);
    target.createContext("/internal/secrets/decrypt", this::handleDecrypt);
    target.createContext("/secrets/rotate-key", this::handleRotateKey);
  }

  public void start() {
    server.start();
  }

  public int port() {
    return boundPort;
  }

  @Override
  public void close() {
    server.stop(0);
  }

  /**
   * Rebuilds the {@link HttpServer}/{@link HttpsServer} from whatever TLS material now sits at
   * {@code gimle.tls.certFile}/{@code keyFile} -- the same stop/rebuild/re-register/restart
   * contract {@code ApiServer.reloadTlsMaterial} already established (see that method's own javadoc
   * for why a rebuild, not a hot-swap, is the only path the JDK actually supports).
   */
  public synchronized void reloadTlsMaterial() throws IOException {
    if (TransportProtocol.fromConfig() == TransportProtocol.PLAINTEXT) {
      return;
    }
    HttpServer previous = server;
    previous.stop(0);
    HttpServer rebuilt = createHttpServer(boundPort);
    registerContexts(rebuilt);
    rebuilt.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
    rebuilt.start();
    server = rebuilt;
    log.info("reloaded TLS material and rebuilt HttpsServer on port {}", boundPort);
  }

  private void handleEncrypt(HttpExchange exchange) {
    try {
      if (!"POST".equals(exchange.getRequestMethod())) {
        respond(exchange, 405, "method not allowed");
        return;
      }
      Map<String, Object> body = Json.asObject(Json.parse(readBody(exchange)));
      byte[] plaintext = decodeBase64((String) body.get("value"));
      byte[] ciphertext = crypto.encrypt(plaintext);
      respondJson(exchange, 200, Map.of("ciphertext", encodeBase64(ciphertext)));
    } catch (IllegalArgumentException e) {
      respondQuietly(exchange, 400, String.valueOf(e.getMessage()));
    } catch (IOException | RuntimeException e) {
      log.warn("encrypt request failed: {}", e.getMessage());
      respondQuietly(exchange, 500, "internal error");
    } finally {
      exchange.close();
    }
  }

  private void handleDecrypt(HttpExchange exchange) {
    try {
      if (!"POST".equals(exchange.getRequestMethod())) {
        respond(exchange, 405, "method not allowed");
        return;
      }
      Map<String, Object> body = Json.asObject(Json.parse(readBody(exchange)));
      List<Object> rawValues = Json.asArray(body.get("values"));
      List<byte[]> ciphertexts = rawValues.stream().map(v -> decodeBase64((String) v)).toList();
      List<byte[]> plaintexts = crypto.decryptBatch(ciphertexts);
      respondJson(
          exchange,
          200,
          Map.of("values", plaintexts.stream().map(FafnirServer::encodeBase64).toList()));
    } catch (IllegalArgumentException | IllegalStateException e) {
      respondQuietly(exchange, 400, String.valueOf(e.getMessage()));
    } catch (IOException | RuntimeException e) {
      log.warn("decrypt request failed: {}", e.getMessage());
      respondQuietly(exchange, 500, "internal error");
    } finally {
      exchange.close();
    }
  }

  /**
   * Same semantics as the {@code ApiServer.handleRotateSecretsKey} endpoint it replaces: generates
   * a new active key and re-encrypts every existing entry under it, gated on the caller having
   * already been authorized upstream (Phase A: {@code gimle-controlplane}'s own {@code
   * requireAuthorized} check; §9's independent Fafnir-side re-check is a later addition, not yet
   * wired in this phase).
   */
  private void handleRotateKey(HttpExchange exchange) {
    try {
      if (!"POST".equals(exchange.getRequestMethod())) {
        respond(exchange, 405, "method not allowed");
        return;
      }
      byte newKeyId = crypto.rotate();
      respondJson(exchange, 200, Map.of("activeKeyId", Byte.toUnsignedInt(newKeyId)));
    } catch (IOException | RuntimeException e) {
      log.warn("secrets key rotation failed: {}", e.getMessage());
      respondQuietly(exchange, 500, "internal error");
    } finally {
      exchange.close();
    }
  }

  private static byte[] decodeBase64(String value) {
    if (value == null) {
      throw new IllegalArgumentException("missing required base64-encoded field");
    }
    return Base64.getDecoder().decode(value);
  }

  private static String encodeBase64(byte[] value) {
    return Base64.getEncoder().encodeToString(value);
  }

  private static String readBody(HttpExchange exchange) throws IOException {
    try (InputStream body = exchange.getRequestBody()) {
      return new String(body.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  private static void respond(HttpExchange exchange, int status, String body) throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("Content-Type", "text/plain; charset=utf-8");
    exchange.sendResponseHeaders(status, bytes.length);
    try (OutputStream out = exchange.getResponseBody()) {
      out.write(bytes);
    }
  }

  private static void respondJson(HttpExchange exchange, int status, Object value)
      throws IOException {
    byte[] bytes = Json.write(value).getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
    exchange.sendResponseHeaders(status, bytes.length);
    try (OutputStream out = exchange.getResponseBody()) {
      out.write(bytes);
    }
  }

  private static void respondQuietly(HttpExchange exchange, int status, String body) {
    try {
      respond(exchange, status, body);
    } catch (IOException e) {
      log.warn("failed to write error response: {}", e.getMessage());
    }
  }
}
