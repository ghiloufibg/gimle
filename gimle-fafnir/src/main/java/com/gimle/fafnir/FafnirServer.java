package com.gimle.fafnir;

import com.gimle.core.authz.Principal;
import com.gimle.core.authz.ResourceKind;
import com.gimle.core.authz.Verb;
import com.gimle.core.protocol.Json;
import com.gimle.core.tls.SslContexts;
import com.gimle.core.tls.TlsSettings;
import com.gimle.core.tls.TransportProtocol;
import com.gimle.mimir.authz.Authorizer;
import com.gimle.pki.Subjects;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsExchange;
import com.sun.net.httpserver.HttpsParameters;
import com.sun.net.httpserver.HttpsServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.Executors;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLPeerUnverifiedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Fafnir's HTTP surface: {@code com.sun.net.httpserver.HttpServer}, JDK-bundled, no framework
 * dependency -- the same minimal stack {@code ApiServer} already uses, for the same reason
 * (CLAUDE.md's explicit non-goal of pulling in Spring/Netty/Quarkus for something this small).
 *
 * <p>Two surfaces live on this server: the internal crypto operations {@code gimle-controlplane}'s
 * {@code ApiServer} needs to keep its existing {@code /config/*} behavior working with crypto now
 * living out-of-process ({@code /internal/secrets/encrypt}, {@code /internal/secrets/decrypt}) plus
 * {@code /secrets/rotate-key}, moved here verbatim from {@code ApiServer.rotateSecretsKey}; and the
 * public, versioned {@code /secrets/{tenantId}/...} surface (design doc §6e/§7), proxied to by
 * {@code ApiServer} but authorized independently here (§9's corrected defense-in-depth).
 */
public final class FafnirServer implements AutoCloseable {

  private static final Logger log = LoggerFactory.getLogger(FafnirServer.class);

  // gimle-controlplane's own claim about who originated a proxied /secrets/* request -- trusted
  // only because it arrives over this mTLS-authenticated connection (the same "channel
  // authenticated, not the claim itself" trust boundary Kubernetes' aggregation layer establishes
  // for its own X-Remote-User/X-Remote-Group headers), never treated as itself proof of
  // authorization (see #authorizeSecrets).
  private static final String FORWARDED_PRINCIPAL_HEADER = "X-Gimle-Forwarded-Principal";
  private static final String FORWARDED_GROUPS_HEADER = "X-Gimle-Forwarded-Groups";

  private final FafnirCrypto crypto;
  private final SecretStore secretStore;
  private final Authorizer authorizer;
  // Not final: a TLS rotation rebuilds this the same way ApiServer's own #server field does --
  // see that class's field javadoc for why a rebuild, not a hot-swap, is the only supported path.
  private volatile HttpServer server;
  private final int boundPort;

  public FafnirServer(FafnirCrypto crypto, int port) throws IOException {
    this.crypto = crypto;
    this.secretStore = new SecretStore(crypto.storeClient(), crypto);
    this.authorizer = new Authorizer(crypto.storeClient());
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
    target.createContext("/secrets/", this::handleSecrets);
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
   * requireAuthorized} check; §9's independent Fafnir-side re-check is wired for the versioned
   * {@code /secrets/*} surface below, not yet extended to this cluster-wide, non-tenant-scoped
   * operation).
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

  // ---- /secrets/{tenantId}, /secrets/{tenantId}/{key}[/versions] (design doc §6e) ----

  private void handleSecrets(HttpExchange exchange) {
    try {
      String tail = pathSegmentAfter(exchange, "/secrets/");
      String[] parts = tail.split("/", 3);
      String tenantId = parts.length > 0 ? parts[0] : "";
      if (tenantId.isBlank()) {
        respond(exchange, 400, "missing tenantId");
        return;
      }
      if (parts.length == 1) {
        if (!"GET".equals(exchange.getRequestMethod())) {
          respond(exchange, 405, "method not allowed");
          return;
        }
        if (authorizeSecrets(exchange, Verb.READ, tenantId)) {
          handleListSecrets(exchange, tenantId);
        }
        return;
      }
      String key = parts[1];
      if (key.isBlank()) {
        respond(exchange, 400, "missing key");
        return;
      }
      if (parts.length == 3) {
        if (!"versions".equals(parts[2])) {
          respond(exchange, 404, "unknown secrets sub-resource: " + parts[2]);
          return;
        }
        if (!"GET".equals(exchange.getRequestMethod())) {
          respond(exchange, 405, "method not allowed");
          return;
        }
        if (authorizeSecrets(exchange, Verb.READ, tenantId)) {
          handleSecretVersions(exchange, tenantId, key);
        }
        return;
      }
      switch (exchange.getRequestMethod()) {
        case "GET" -> {
          if (authorizeSecrets(exchange, Verb.READ, tenantId)) {
            handleGetSecret(exchange, tenantId, key);
          }
        }
        case "PUT" -> {
          if (authorizeSecrets(exchange, Verb.WRITE, tenantId)) {
            handlePutSecret(exchange, tenantId, key);
          }
        }
        case "DELETE" -> {
          if (authorizeSecrets(exchange, Verb.DELETE, tenantId)) {
            handleDeleteSecret(exchange, tenantId, key);
          }
        }
        default -> respond(exchange, 405, "method not allowed");
      }
    } catch (IllegalArgumentException e) {
      respondQuietly(exchange, 400, String.valueOf(e.getMessage()));
    } catch (IOException | RuntimeException e) {
      log.warn("secrets request failed: {}", e.getMessage());
      respondQuietly(exchange, 500, "internal error");
    } finally {
      exchange.close();
    }
  }

  private void handleListSecrets(HttpExchange exchange, String tenantId) throws IOException {
    List<Map<String, Object>> secrets =
        secretStore.list(tenantId).stream().map(FafnirServer::secretMetadataToJson).toList();
    respondJson(exchange, 200, Map.of("secrets", secrets));
  }

  private void handleSecretVersions(HttpExchange exchange, String tenantId, String key)
      throws IOException {
    if (!secretStore.exists(tenantId, key)) {
      respond(exchange, 404, "no such secret: " + key);
      return;
    }
    respondJson(exchange, 200, Map.of("versions", secretStore.versions(tenantId, key)));
  }

  private void handleGetSecret(HttpExchange exchange, String tenantId, String key)
      throws IOException {
    OptionalInt version = parseVersion(exchange);
    Optional<byte[]> value = secretStore.get(tenantId, key, version);
    if (value.isEmpty()) {
      respond(exchange, 404, "no such secret: " + key);
      return;
    }
    int effectiveVersion =
        version.orElseGet(
            () ->
                secretStore.versions(tenantId, key).stream().max(Integer::compareTo).orElseThrow());
    respondJson(
        exchange, 200, Map.of("value", encodeBase64(value.get()), "version", effectiveVersion));
  }

  private void handlePutSecret(HttpExchange exchange, String tenantId, String key)
      throws IOException {
    Map<String, Object> body = Json.asObject(Json.parse(readBody(exchange)));
    byte[] plaintext = decodeBase64((String) body.get("value"));
    int version = secretStore.put(tenantId, key, plaintext);
    respondJson(exchange, 200, Map.of("version", version));
  }

  private void handleDeleteSecret(HttpExchange exchange, String tenantId, String key)
      throws IOException {
    boolean destroy = "true".equals(parseQuery(exchange).get("destroy"));
    boolean existed =
        destroy ? secretStore.hardDelete(tenantId, key) : secretStore.softDelete(tenantId, key);
    if (!existed) {
      respond(exchange, 404, "no such secret: " + key);
      return;
    }
    respond(exchange, 200, "ok");
  }

  private static Map<String, Object> secretMetadataToJson(SecretMetadata metadata) {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("key", metadata.key());
    map.put("latestVersion", metadata.latestVersion());
    map.put("deleted", metadata.deleted());
    return map;
  }

  private static OptionalInt parseVersion(HttpExchange exchange) {
    String raw = parseQuery(exchange).get("version");
    if (raw == null || raw.isBlank()) {
      return OptionalInt.empty();
    }
    try {
      return OptionalInt.of(Integer.parseInt(raw));
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException("invalid version: " + raw);
    }
  }

  /**
   * Fafnir's own, independent authorization decision (design doc §9's corrected defense-in-depth):
   * never treats "this request arrived already-forwarded by gimle-controlplane" as proof of
   * authorization by itself -- reads RBAC data through its own {@link Authorizer}/{@code
   * StoreClient} and reaches its own conclusion, so a buggy or compromised control-plane replica
   * that forwards an unauthorized principal is still caught here. Same default-plaintext posture as
   * every other Gimlé process (§9): no TLS means no identity to check in the first place, so every
   * request passes -- {@code gimle.transport.protocol=tls} is the one switch that turns this check
   * on, cluster-wide.
   */
  private boolean authorizeSecrets(HttpExchange exchange, Verb verb, String tenantId) {
    if (!(exchange instanceof HttpsExchange)) {
      return true;
    }
    Optional<Principal> principal = resolvePrincipal(exchange);
    if (principal.isEmpty()) {
      respondQuietly(exchange, 401, "authentication required");
      return false;
    }
    if (!authorizer.authorize(
        principal.get(), ResourceKind.SECRET, verb, Optional.of(tenantId), Optional.empty())) {
      respondQuietly(exchange, 403, "forbidden");
      return false;
    }
    return true;
  }

  /**
   * A forwarded principal (set only by {@code ApiServer}'s own {@code /secrets/*} proxy) wins over
   * the connection's own peer certificate when both are present, since a proxied request's peer
   * certificate identifies the control-plane replica making the call, not the human or node that
   * originated it. Falls back to the peer certificate directly for a caller reaching Fafnir without
   * going through the proxy at all (a node agent's own direct fetch, design doc §9's third
   * subsection, or a test simulating one).
   */
  private Optional<Principal> resolvePrincipal(HttpExchange exchange) {
    Optional<String> forwardedName = firstHeader(exchange, FORWARDED_PRINCIPAL_HEADER);
    if (forwardedName.isPresent()) {
      Set<String> groups = new LinkedHashSet<>(splitHeader(exchange, FORWARDED_GROUPS_HEADER));
      return Optional.of(new Principal(forwardedName.get(), groups));
    }
    return peerCertificate(exchange).map(Subjects::principalFrom);
  }

  private static Optional<String> firstHeader(HttpExchange exchange, String name) {
    List<String> values = exchange.getRequestHeaders().get(name);
    return values == null || values.isEmpty() ? Optional.empty() : Optional.of(values.get(0));
  }

  private static List<String> splitHeader(HttpExchange exchange, String name) {
    Optional<String> value = firstHeader(exchange, name);
    if (value.isEmpty() || value.get().isBlank()) {
      return List.of();
    }
    return List.of(value.get().split(","));
  }

  private static Optional<X509Certificate> peerCertificate(HttpExchange exchange) {
    if (!(exchange instanceof HttpsExchange httpsExchange)) {
      return Optional.empty();
    }
    try {
      Certificate[] certificates = httpsExchange.getSSLSession().getPeerCertificates();
      return certificates.length > 0 && certificates[0] instanceof X509Certificate x509
          ? Optional.of(x509)
          : Optional.empty();
    } catch (SSLPeerUnverifiedException e) {
      return Optional.empty();
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

  private static String pathSegmentAfter(HttpExchange exchange, String prefix) {
    String path = exchange.getRequestURI().getPath();
    return path.substring(prefix.length());
  }

  private static Map<String, String> parseQuery(HttpExchange exchange) {
    Map<String, String> result = new LinkedHashMap<>();
    String query = exchange.getRequestURI().getRawQuery();
    if (query == null || query.isBlank()) {
      return result;
    }
    for (String pair : query.split("&")) {
      int eq = pair.indexOf('=');
      if (eq < 0) {
        continue;
      }
      String key = URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8);
      String value = URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
      result.put(key, value);
    }
    return result;
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
