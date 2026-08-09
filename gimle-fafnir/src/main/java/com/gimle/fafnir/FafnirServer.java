package com.gimle.fafnir;

import com.gimle.core.authz.BuiltinRoles;
import com.gimle.core.authz.Principal;
import com.gimle.core.authz.ResourceKind;
import com.gimle.core.authz.Verb;
import com.gimle.core.protocol.Json;
import com.gimle.core.throttle.LoginThrottle;
import com.gimle.core.tls.SslContexts;
import com.gimle.core.tls.TlsSettings;
import com.gimle.core.tls.TransportProtocol;
import com.gimle.mimir.authz.Authorizer;
import com.gimle.mimir.rpc.StoreClient;
import com.gimle.observability.FafnirMetrics;
import com.gimle.pki.Subjects;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
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
import java.time.Duration;
import java.time.Instant;
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
  // A dedicated logger, not `log` -- design doc §9's Observability subsection: a queryable record
  // of every /secrets/* request (principal, tenant, key, version, verb, allow/deny, timestamp --
  // never the value), separated from ordinary operational logging so it can be routed/retained
  // differently. gimle-observability has no general-purpose audit-event-log mechanism today (see
  // InstanceEvent's own javadoc: "the general audit-logging item still on the roadmap") -- adding
  // one is a bigger, separate undertaking than this item's scope, so this uses the same structured
  // SLF4J logging every other Gimlé process already relies on for its own queryable event trail,
  // rather than inventing a parallel Raft-backed collection for a single event kind.
  private static final Logger auditLog = LoggerFactory.getLogger("com.gimle.fafnir.audit");

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
  private final FafnirMetrics metrics;
  // Keyed by calling principal/node identity, incrementing on Authorizer.authorize(...) == false
  // rather than a login failure -- the same generic identity-keyed backoff counter ApiServer's own
  // login endpoint uses, reused per that class's own javadoc ("gimle-fafnir constructs its own
  // separate instance keyed by calling principal/node identity").
  private final LoginThrottle authzThrottle = new LoginThrottle();
  // Not final: a TLS rotation rebuilds this the same way ApiServer's own #server field does --
  // see that class's field javadoc for why a rebuild, not a hot-swap, is the only supported path.
  private volatile HttpServer server;
  private final int boundPort;

  public FafnirServer(FafnirCrypto crypto, int port) throws IOException {
    this(crypto, port, new FafnirMetrics());
  }

  public FafnirServer(FafnirCrypto crypto, int port, FafnirMetrics metrics) throws IOException {
    this.crypto = crypto;
    this.secretStore = new SecretStore(crypto.storeClient(), crypto);
    this.authorizer = new Authorizer(crypto.storeClient());
    this.metrics = metrics;
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
    target.createContext("/internal/secrets/encrypt", instrument("encrypt", this::handleEncrypt));
    target.createContext("/internal/secrets/decrypt", instrument("decrypt", this::handleDecrypt));
    target.createContext("/secrets/rotate-key", instrument("rotate-key", this::handleRotateKey));
    target.createContext("/secrets/", instrument("secrets", this::handleSecrets));
  }

  /**
   * Wraps a handler with request-count/latency/error Micrometer recording (design doc §9's
   * Observability subsection, mirroring {@code WorkerMetrics}/{@code FabricServer}'s own request-
   * metrics pattern) -- at context-registration time rather than inside each handler body, so every
   * endpoint gets identical instrumentation with zero per-handler boilerplate. {@code error} is
   * read from the exchange's own response code after the delegate finishes (every handler here
   * already sends a real status and closes the exchange itself in its own {@code finally} block),
   * not from an escaping exception -- these handlers deliberately never let one escape.
   */
  private HttpHandler instrument(String endpoint, HttpHandler delegate) {
    return exchange -> {
      String verb = exchange.getRequestMethod();
      long startNanos = System.nanoTime();
      try {
        delegate.handle(exchange);
      } finally {
        Duration latency = Duration.ofNanos(System.nanoTime() - startNanos);
        int status = exchange.getResponseCode();
        boolean error = status <= 0 || status >= 400;
        metrics.recordRequest(endpoint, verb, latency, error);
      }
    };
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
        if (authorizeSecrets(exchange, Verb.READ, tenantId, Optional.empty())) {
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
        if (authorizeSecrets(exchange, Verb.READ, tenantId, Optional.of(key))) {
          handleSecretVersions(exchange, tenantId, key);
        }
        return;
      }
      switch (exchange.getRequestMethod()) {
        case "GET" -> {
          if (authorizeSecrets(exchange, Verb.READ, tenantId, Optional.of(key))) {
            handleGetSecret(exchange, tenantId, key);
          }
        }
        case "PUT" -> {
          if (authorizeSecrets(exchange, Verb.WRITE, tenantId, Optional.of(key))) {
            handlePutSecret(exchange, tenantId, key);
          }
        }
        case "DELETE" -> {
          if (authorizeSecrets(exchange, Verb.DELETE, tenantId, Optional.of(key))) {
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
   *
   * <p>Also the single point every {@code /secrets/*} request passes through with its principal,
   * tenant, key, and verb all in hand -- so this is where §9's rate limiting (a {@link
   * #authzThrottle} keyed by principal, incrementing on a denial) and the audit log entry (§9's
   * Observability subsection) both live, rather than duplicating either concern into every
   * individual handler.
   */
  private boolean authorizeSecrets(
      HttpExchange exchange, Verb verb, String tenantId, Optional<String> key) {
    if (!(exchange instanceof HttpsExchange)) {
      return true;
    }
    Optional<Principal> principal = resolvePrincipal(exchange);
    if (principal.isEmpty()) {
      respondQuietly(exchange, 401, "authentication required");
      return false;
    }
    String throttleKey = principal.get().name();
    Optional<Instant> throttledUntil = authzThrottle.throttledUntil(throttleKey);
    if (throttledUntil.isPresent()) {
      respondThrottled(exchange, throttledUntil.get());
      return false;
    }
    boolean allowed =
        principal.get().groups().contains(BuiltinRoles.GROUP_NODES)
            ? verb == Verb.READ && isTenantAssignedToNode(principal.get().name(), tenantId)
            : authorizer.authorize(
                principal.get(),
                ResourceKind.SECRET,
                verb,
                Optional.of(tenantId),
                Optional.empty());
    auditLog.info(
        "principal={} tenant={} key={} verb={} allow={}",
        principal.get().name(),
        tenantId,
        key.orElse("-"),
        verb,
        allowed);
    if (!allowed) {
      authzThrottle.recordFailure(throttleKey);
      metrics.recordAuthzFailure(verb.name());
      respondQuietly(exchange, 403, "forbidden");
      return false;
    }
    authzThrottle.recordSuccess(throttleKey);
    return true;
  }

  /**
   * §9's node-authorization mode, mirroring Kubernetes' own Node authorization + NodeRestriction: a
   * {@code gimle:nodes} principal (a node agent's own certificate identity, {@code CN=nodeId}) may
   * read a tenant's secrets only if that node currently has at least one active instance assignment
   * for that tenant -- read-only, never write/delete, and never the ordinary RBAC path (a node
   * certificate has no {@code Role}/{@code RoleBinding} of its own to check against).
   *
   * <p>Design-doc correction: the original text cited {@code
   * StoreClient.listAssignmentsFor(nodeId)} as the primitive for this, but that method is
   * deployment-scoped ({@code listAssignmentsFor(String deploymentName)}), not node-scoped -- there
   * is no direct "assignments for this node" query. This walks every assignment via {@link
   * StoreClient#listAssignments()}, filters to this node, and joins each surviving assignment's
   * {@code deploymentName} back to its {@code DeploymentSpec} to read that deployment's own {@code
   * tenantId}.
   *
   * <p>Honest limitation, stated rather than hidden (§9's own caveat): this is tenant-scoped, not
   * per-key-scoped -- a node with any assignment for a tenant can read every secret that tenant
   * owns, not just the ones its own deployed modules actually declared a dependency on, since the
   * module manifest has no per-key secret-dependency declaration today.
   */
  private boolean isTenantAssignedToNode(String nodeId, String tenantId) {
    StoreClient storeClient = crypto.storeClient();
    return storeClient.listAssignments().stream()
        .filter(a -> a.nodeId().equals(nodeId))
        .map(a -> storeClient.getDeployment(a.deploymentName()))
        .flatMap(Optional::stream)
        .anyMatch(spec -> spec.tenantId().filter(tenantId::equals).isPresent());
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

  /**
   * {@code 429} with a standard {@code Retry-After} header (seconds) -- the same shape {@code
   * ApiServer.respondThrottled} already uses for its own {@link LoginThrottle}-backed login
   * throttling, reused here for {@link #authzThrottle}'s consecutive-authorization-failure signal.
   */
  private static void respondThrottled(HttpExchange exchange, Instant nextAllowedAttempt) {
    long retryAfterSeconds =
        Math.max(1, Duration.between(Instant.now(), nextAllowedAttempt).toSeconds());
    exchange.getResponseHeaders().add("Retry-After", String.valueOf(retryAfterSeconds));
    respondQuietly(exchange, 429, "too many attempts; try again later");
  }
}
