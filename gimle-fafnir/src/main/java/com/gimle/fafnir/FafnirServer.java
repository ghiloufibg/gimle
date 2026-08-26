package com.gimle.fafnir;

import com.gimle.core.authz.Account;
import com.gimle.core.authz.BuiltinRoles;
import com.gimle.core.authz.PasswordHashes;
import com.gimle.core.authz.Principal;
import com.gimle.core.authz.ResourceKind;
import com.gimle.core.authz.Verb;
import com.gimle.core.exception.GimleSecretsException;
import com.gimle.core.protocol.AuditEvent;
import com.gimle.core.protocol.Json;
import com.gimle.core.session.SessionKeyFileManager;
import com.gimle.core.session.SessionTokens;
import com.gimle.core.tenant.Tenant;
import com.gimle.core.throttle.LoginThrottle;
import com.gimle.core.tls.SslContexts;
import com.gimle.core.tls.TlsSettings;
import com.gimle.core.tls.TransportProtocol;
import com.gimle.core.web.HttpResponses;
import com.gimle.core.web.RootRedirectHandler;
import com.gimle.core.web.SpaStaticHandler;
import com.gimle.fafnir.secret.SealCipher;
import com.gimle.fafnir.secretmap.SecretMapCodec;
import com.gimle.fafnir.secretmap.SecretMapStore;
import com.gimle.mimir.authz.Authorizer;
import com.gimle.mimir.raft.StateMutation;
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
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import javax.crypto.SecretKey;
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
 * public, versioned {@code /secrets/{tenantId}/...} surface, proxied to by {@code ApiServer} but
 * authorized independently here -- never trusting the proxy's own forwarded claim as proof of
 * authorization by itself.
 */
public final class FafnirServer implements AutoCloseable {

  private static final Logger log = LoggerFactory.getLogger(FafnirServer.class);
  // A dedicated logger, not `log` -- every /secrets/*
  // request (principal, tenant, key, verb, allow/deny) also lands in the durable, queryable
  // AuditEvent trail (see #authorizeSecrets), but this line has independent value for an operator
  // tailing this process's own log file directly, no query needed.
  private static final Logger auditLog = LoggerFactory.getLogger("com.gimle.fafnir.audit");

  // gimle-controlplane's own claim about who originated a proxied /secrets/* request -- trusted
  // only because it arrives over this mTLS-authenticated connection (the same "channel
  // authenticated, not the claim itself" trust boundary Kubernetes' aggregation layer establishes
  // for its own X-Remote-User/X-Remote-Group headers), never treated as itself proof of
  // authorization (see #authorizeSecrets).
  private static final String FORWARDED_PRINCIPAL_HEADER = "X-Gimle-Forwarded-Principal";
  private static final String FORWARDED_GROUPS_HEADER = "X-Gimle-Forwarded-Groups";

  // Fafnir's own console session cookie -- deliberately a distinct name from ApiServer's
  // "gimle_session", even though the two never collide in a browser regardless (different origin
  // per process/port): a developer inspecting cookies in devtools with both consoles open should
  // still be able to tell which is which at a glance.
  private static final String SESSION_COOKIE_NAME = "gimle_fafnir_session";
  private static final Duration SESSION_TTL = Duration.ofHours(12);

  private final FafnirCrypto crypto;
  private final SecretStore secretStore;
  private final SecretMapStore secretMapStore;
  private final SealingCrypto sealingCrypto;
  private final Authorizer authorizer;
  private final FafnirMetrics metrics;
  private final Instant startedAt = Instant.now();
  // Signs/verifies Fafnir's own console session cookies -- deliberately never shared with
  // ApiServer's own signing key (see SessionKeyFileManager's own javadoc): each console's session
  // is its own, matching Fafnir's broader defense-in-depth posture of never trusting
  // "authenticated somewhere else" as proof by itself. Co-located next to the secret key ring
  // file, the same convention ApiServer's own sessionSigningKey field derivation uses.
  private final SecretKey sessionSigningKey;
  // Keyed by calling principal/node identity, incrementing on Authorizer.authorize(...) == false
  // rather than a login failure -- the same generic identity-keyed backoff counter ApiServer's own
  // login endpoint uses, reused per that class's own javadoc ("gimle-fafnir constructs its own
  // separate instance keyed by calling principal/node identity").
  private final LoginThrottle authzThrottle = new LoginThrottle();
  // A second, separate instance for /auth/login specifically -- distinct failure semantics
  // (wrong-password attempts, not authorization denials) from authzThrottle above, the same
  // username-and-address-keyed split ApiServer's own login endpoint already establishes.
  private final LoginThrottle loginThrottle = new LoginThrottle();
  // Remembered so a TLS-reload rebuild (see #reloadTlsMaterial) re-registers the console context
  // too, exactly like ApiServer's own consoleStaticRoot field.
  private volatile Optional<Path> consoleStaticRoot = Optional.empty();
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
    this.secretMapStore = new SecretMapStore(crypto.storeClient(), secretStore);
    this.sealingCrypto =
        new SealingCrypto(crypto.secretKeyFilePath().resolveSibling("sealing.key"));
    this.authorizer = new Authorizer(crypto.storeClient());
    this.metrics = metrics;
    this.sessionSigningKey =
        SessionKeyFileManager.loadOrCreate(
            crypto.secretKeyFilePath().resolveSibling("session.key"));
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

  private void registerContexts(HttpServer target) throws IOException {
    target.createContext("/internal/secrets/encrypt", instrument("encrypt", this::handleEncrypt));
    target.createContext("/internal/secrets/decrypt", instrument("decrypt", this::handleDecrypt));
    target.createContext("/secrets/rotate-key", instrument("rotate-key", this::handleRotateKey));
    target.createContext(
        "/secrets/retire-key", instrument("retire-key", this::handleRetireSecretsKey));
    target.createContext("/secrets/", instrument("secrets", this::handleSecrets));
    target.createContext("/secretmaps/", instrument("secretmaps", this::handleSecretMaps));
    target.createContext(
        "/seal/public-key", instrument("seal-public-key", this::handleSealPublicKey));
    target.createContext(
        "/seal/rotate-key", instrument("seal-rotate-key", this::handleSealRotateKey));
    target.createContext(
        "/seal/retire-key", instrument("seal-retire-key", this::handleSealRetireKey));
    target.createContext("/auth/login", instrument("auth-login", this::handleAuthLogin));
    target.createContext("/auth/logout", instrument("auth-logout", this::handleAuthLogout));
    target.createContext("/auth/session", instrument("auth-session", this::handleAuthSession));
    target.createContext("/status", instrument("status", this::handleStatus));
    if (consoleStaticRoot.isPresent()) {
      registerConsole(target, consoleStaticRoot.get());
    }
  }

  /**
   * Registers a static-file context at {@code /console} serving Fafnir's own bundled web console
   * (see {@code gimle-fafnir-console}'s {@code pom.xml}) -- the same opt-in, remembered-for-later-
   * re-registration shape {@code ApiServer.serveConsole} already established, sharing its {@link
   * SpaStaticHandler} rather than each process carrying its own copy. Also registers the same
   * {@code /} redirect to {@code /console} the control plane has, so the bare root address lands on
   * the console instead of the JDK server's own {@code 404}.
   */
  public void serveConsole(Path staticRoot) throws IOException {
    consoleStaticRoot = Optional.of(staticRoot);
    registerConsole(server, staticRoot);
  }

  private static void registerConsole(HttpServer target, Path staticRoot) throws IOException {
    String shellFileName =
        Files.isRegularFile(staticRoot.resolve("_shell.html")) ? "_shell.html" : "index.html";
    target.createContext("/console", new SpaStaticHandler(staticRoot, shellFileName));
    target.createContext("/", new RootRedirectHandler("/console"));
  }

  /**
   * Wraps a handler with request-count/latency/error Micrometer recording, mirroring {@code
   * WorkerMetrics}/{@code FabricServer}'s own request-metrics pattern -- at context-registration
   * time rather than inside each handler body, so every endpoint gets identical instrumentation
   * with zero per-handler boilerplate. {@code error} is read from the exchange's own response code
   * after the delegate finishes (every handler here already sends a real status and closes the
   * exchange itself in its own {@code finally} block), not from an escaping exception -- these
   * handlers deliberately never let one escape.
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

  /**
   * Public so {@code FafnirMain} can hand this registry to a {@code MuninnShipper} when {@code
   * -Dgimle.fafnir.muninnEndpoint} is configured -- the same shape {@code ApiServer#metrics()}
   * already established for the control plane.
   */
  public FafnirMetrics metrics() {
    return metrics;
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
    } catch (IllegalArgumentException | IllegalStateException | GimleSecretsException e) {
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
   * already been authorized upstream ({@code gimle-controlplane}'s own {@code requireAuthorized}
   * check). Fafnir's own independent re-check below is wired for the versioned {@code /secrets/*}
   * surface, not yet extended to this cluster-wide, non-tenant-scoped operation.
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

  /**
   * Sibling of {@link #handleRotateKey}, same unauthenticated-at-this-layer posture (the proxy is
   * the actual trust boundary -- see that method's own class javadoc): {@code keyId} is a
   * caller-named id to actually stop trusting, not a value Fafnir chooses itself, so a bad id or a
   * request naming the still-active key surfaces as 400, distinct from a genuine internal error.
   */
  private void handleRetireSecretsKey(HttpExchange exchange) {
    try {
      if (!"POST".equals(exchange.getRequestMethod())) {
        respond(exchange, 405, "method not allowed");
        return;
      }
      byte keyId = parseKeyIdBody(exchange);
      byte retired = crypto.retire(keyId);
      respondJson(exchange, 200, Map.of("retiredKeyId", Byte.toUnsignedInt(retired)));
    } catch (GimleSecretsException | IllegalArgumentException e) {
      respondQuietly(exchange, 400, String.valueOf(e.getMessage()));
    } catch (IOException | RuntimeException e) {
      log.warn("secrets key retirement failed: {}", e.getMessage());
      respondQuietly(exchange, 500, "internal error");
    } finally {
      exchange.close();
    }
  }

  // ---- /seal/public-key, /seal/rotate-key, /seal/retire-key ----

  /**
   * Unauthenticated for a different reason than the two handlers above: not "the proxy is the real
   * trust boundary," but that the key is meant to be public -- a check here would protect nothing,
   * and would only get in the way of the caller this endpoint exists for: one with no Fafnir
   * credentials at all, sealing a value offline before it can commit one.
   */
  private void handleSealPublicKey(HttpExchange exchange) {
    try {
      if (!"GET".equals(exchange.getRequestMethod())) {
        respond(exchange, 405, "method not allowed");
        return;
      }
      respondJson(
          exchange,
          200,
          Map.of(
              "sealingKeyId", Byte.toUnsignedInt(sealingCrypto.activeSealingKeyId()),
              "publicKey", encodeBase64(sealingCrypto.activePublicKey().getEncoded()),
              "algorithm", "RSA-OAEP-SHA256"));
    } catch (IOException | RuntimeException e) {
      log.warn("seal public key lookup failed: {}", e.getMessage());
      respondQuietly(exchange, 500, "internal error");
    } finally {
      exchange.close();
    }
  }

  private void handleSealRotateKey(HttpExchange exchange) {
    try {
      if (!"POST".equals(exchange.getRequestMethod())) {
        respond(exchange, 405, "method not allowed");
        return;
      }
      byte newKeyId = sealingCrypto.rotate();
      respondJson(exchange, 200, Map.of("activeSealingKeyId", Byte.toUnsignedInt(newKeyId)));
    } catch (IOException | RuntimeException e) {
      log.warn("sealing key rotation failed: {}", e.getMessage());
      respondQuietly(exchange, 500, "internal error");
    } finally {
      exchange.close();
    }
  }

  private void handleSealRetireKey(HttpExchange exchange) {
    try {
      if (!"POST".equals(exchange.getRequestMethod())) {
        respond(exchange, 405, "method not allowed");
        return;
      }
      byte keyId = parseKeyIdBody(exchange);
      byte retired = sealingCrypto.retire(keyId);
      respondJson(exchange, 200, Map.of("retiredKeyId", Byte.toUnsignedInt(retired)));
    } catch (GimleSecretsException | IllegalArgumentException e) {
      respondQuietly(exchange, 400, String.valueOf(e.getMessage()));
    } catch (IOException | RuntimeException e) {
      log.warn("sealing key retirement failed: {}", e.getMessage());
      respondQuietly(exchange, 500, "internal error");
    } finally {
      exchange.close();
    }
  }

  private static byte parseKeyIdBody(HttpExchange exchange) throws IOException {
    Map<String, Object> body = Json.asObject(Json.parse(readBody(exchange)));
    return parseKeyId(body.get("keyId"));
  }

  /**
   * Shared range check for every wire-carried key id byte -- {@code parseKeyIdBody} (the
   * rotate/retire request bodies) and {@link #handleSealSecretMap}'s per-entry {@code sealingKeyId}
   * both go through this rather than a raw {@code (byte)} cast, which would silently wrap a
   * too-large value (e.g. 999) into an unrelated in-range byte (231) instead of rejecting it -- a
   * confusing "no sealing key with id 231" for a caller who actually sent 999.
   */
  private static byte parseKeyId(Object raw) {
    if (!(raw instanceof Number number)) {
      throw new IllegalArgumentException("'keyId' must be an integer");
    }
    int value = number.intValue();
    if (value < 0 || value > 255) {
      throw new IllegalArgumentException("'keyId' must be between 0 and 255");
    }
    return (byte) value;
  }

  // ---- /secrets/{tenantId}, /secrets/{tenantId}/{key}[/versions] ----

  private void handleSecrets(HttpExchange exchange) {
    try {
      String tail = pathSegmentAfter(exchange, "/secrets/");
      String[] parts = tail.split("/", 3);
      String tenantId = parts.length > 0 ? parts[0] : "";
      if (tenantId.isBlank()) {
        respond(exchange, 400, "missing tenantId");
        return;
      }
      // GET /secrets/{tenantId} -- list every secret key the tenant owns.
      if (parts.length == 1) {
        if (!"GET".equals(exchange.getRequestMethod())) {
          respond(exchange, 405, "method not allowed");
          return;
        }
        if (authorizeSecrets(
            exchange, ResourceKind.SECRET, Verb.READ, tenantId, Optional.empty())) {
          handleListSecrets(exchange, tenantId);
        }
        return;
      }
      String key = parts[1];
      if (key.isBlank()) {
        respond(exchange, 400, "missing key");
        return;
      }
      // GET /secrets/{tenantId}/{key}/versions -- list the key's stored version numbers.
      if (parts.length == 3) {
        if (!"versions".equals(parts[2])) {
          respond(exchange, 404, "unknown secrets sub-resource: " + parts[2]);
          return;
        }
        if (!"GET".equals(exchange.getRequestMethod())) {
          respond(exchange, 405, "method not allowed");
          return;
        }
        if (authorizeSecrets(
            exchange, ResourceKind.SECRET, Verb.READ, tenantId, Optional.of(key))) {
          handleSecretVersions(exchange, tenantId, key);
        }
        return;
      }
      // GET/PUT/DELETE /secrets/{tenantId}/{key}[?version=N|destroy=true] -- read, write, or
      // (soft- or, with ?destroy=true, hard-) delete a single secret. PUT/DELETE reject a
      // SecretMap-owned key outright -- see #handleSecretMaps's own javadoc for why mutation, but
      // not read, is blocked on this flat path.
      switch (exchange.getRequestMethod()) {
        case "GET" -> {
          if (authorizeSecrets(
              exchange, ResourceKind.SECRET, Verb.READ, tenantId, Optional.of(key))) {
            handleGetSecret(exchange, tenantId, key);
          }
        }
        case "PUT" -> {
          if (SecretMapCodec.isSecretMapKey(key)) {
            respond(exchange, 400, "key is reserved for a SecretMap; use /secretmaps/* instead");
            return;
          }
          if (authorizeSecrets(
              exchange, ResourceKind.SECRET, Verb.WRITE, tenantId, Optional.of(key))) {
            handlePutSecret(exchange, tenantId, key);
          }
        }
        case "DELETE" -> {
          if (SecretMapCodec.isSecretMapKey(key)) {
            respond(exchange, 400, "key is reserved for a SecretMap; use /secretmaps/* instead");
            return;
          }
          if (authorizeSecrets(
              exchange, ResourceKind.SECRET, Verb.DELETE, tenantId, Optional.of(key))) {
            handleDeleteSecret(exchange, tenantId, key);
          }
        }
        default -> respond(exchange, 405, "method not allowed");
      }
    } catch (IllegalArgumentException | GimleSecretsException e) {
      respondQuietly(exchange, 400, String.valueOf(e.getMessage()));
    } catch (IOException | RuntimeException e) {
      log.warn("secrets request failed: {}", e.getMessage());
      respondQuietly(exchange, 500, "internal error");
    } finally {
      exchange.close();
    }
  }

  private void handleListSecrets(HttpExchange exchange, String tenantId) throws IOException {
    // Filters out SecretMap-owned rows, mirroring how ApiServer already filters ConfigMapCodec/
    // isFafnirManagedSecretKey rows out of a plain /config/* listing -- a SecretMap's members have
    // their own listing under /secretmaps/{tenantId}, not mixed into the tenant's flat secrets.
    // Also omits a soft-deleted secret, matching Vault KV v2's own "deleted means not listed"
    // posture (the same rule #get already applies to a latest-version read) -- its history is
    // still reachable via GET .../{key}/versions and an explicit ?version=N read, this is only
    // the default listing.
    List<Map<String, Object>> secrets =
        secretStore.list(tenantId).stream()
            .filter(meta -> !SecretMapCodec.isSecretMapKey(meta.key()))
            .filter(meta -> !meta.deleted())
            .map(FafnirServer::secretMetadataToJson)
            .toList();
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
    // Idempotent, matching every other resource kind's own delete-of-a-never-existed-name
    // convention (deployment/job/tenant/role/account/config/etc. all no-op successfully rather
    // than 404) -- whether it existed or not is not reported back, deliberately.
    boolean destroy = "true".equals(parseQuery(exchange).get("destroy"));
    if (destroy) {
      secretStore.hardDelete(tenantId, key);
    } else {
      secretStore.softDelete(tenantId, key);
    }
    respond(exchange, 200, "ok");
  }

  // ---- /secretmaps/{tenantId}[?names=a,b,c], /secretmaps/{tenantId}/{name}[/{key}] ----

  /**
   * The SecretMap surface, authorized independently under {@link ResourceKind#SECRETMAP} rather
   * than {@link ResourceKind#SECRET} -- the same split {@code ConfigMap}/{@code Config} already
   * establishes, so a role can be granted "read flat secrets" without also getting "read named
   * SecretMaps." {@code GET /secretmaps/{tenantId}} without {@code ?names=} returns metadata-only
   * names (mirroring {@link #handleListSecrets}'s own posture); with {@code ?names=a,b,c} it
   * returns decrypted values for exactly those names -- the value-bearing batch fetch {@code
   * gimle-agent} calls directly to deliver only the SecretMaps a deployment's {@code secretMapRefs}
   * named, instead of every secret the tenant owns.
   */
  private void handleSecretMaps(HttpExchange exchange) {
    try {
      String tail = pathSegmentAfter(exchange, "/secretmaps/");
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
        if (!authorizeSecrets(
            exchange, ResourceKind.SECRETMAP, Verb.READ, tenantId, Optional.empty())) {
          return;
        }
        String namesParam = parseQuery(exchange).get("names");
        if (namesParam == null || namesParam.isBlank()) {
          handleListSecretMapNames(exchange, tenantId);
        } else {
          handleGetSecretMapValues(exchange, tenantId, List.of(namesParam.split(",")));
        }
        return;
      }
      String name = parts[1];
      if (name.isBlank()) {
        respond(exchange, 400, "missing SecretMap name");
        return;
      }
      if (parts.length == 3) {
        // GET /secretmaps/{tenantId}/{name}/versions and POST .../rollback are reserved action
        // segments, checked before the general "third segment is a member key" fallback below --
        // a real key literally named "versions"/"rollback" only ever reaches this branch via
        // DELETE, which these two never intercept.
        if ("versions".equals(parts[2]) && "GET".equals(exchange.getRequestMethod())) {
          if (authorizeSecrets(
              exchange, ResourceKind.SECRETMAP, Verb.READ, tenantId, Optional.of(name))) {
            handleListSecretMapGroupVersions(exchange, tenantId, name);
          }
          return;
        }
        if ("rollback".equals(parts[2]) && "POST".equals(exchange.getRequestMethod())) {
          if (authorizeSecrets(
              exchange, ResourceKind.SECRETMAP, Verb.WRITE, tenantId, Optional.of(name))) {
            handleRollbackSecretMap(exchange, tenantId, name);
          }
          return;
        }
        if ("seal".equals(parts[2]) && "POST".equals(exchange.getRequestMethod())) {
          if (authorizeSecrets(
              exchange, ResourceKind.SECRETMAP, Verb.WRITE, tenantId, Optional.of(name))) {
            handleSealSecretMap(exchange, tenantId, name);
          }
          return;
        }
        // DELETE /secretmaps/{tenantId}/{name}/{key}[?destroy=true] -- a single member key.
        String key = parts[2];
        if (!"DELETE".equals(exchange.getRequestMethod())) {
          respond(exchange, 405, "method not allowed");
          return;
        }
        if (authorizeSecrets(
            exchange, ResourceKind.SECRETMAP, Verb.DELETE, tenantId, Optional.of(name))) {
          handleDeleteSecretMapKey(exchange, tenantId, name, key);
        }
        return;
      }
      switch (exchange.getRequestMethod()) {
        case "GET" -> {
          if (authorizeSecrets(
              exchange, ResourceKind.SECRETMAP, Verb.READ, tenantId, Optional.of(name))) {
            handleGetSecretMapMetadata(exchange, tenantId, name);
          }
        }
        case "PUT" -> {
          if (authorizeSecrets(
              exchange, ResourceKind.SECRETMAP, Verb.WRITE, tenantId, Optional.of(name))) {
            handlePutSecretMap(exchange, tenantId, name);
          }
        }
        case "DELETE" -> {
          if (authorizeSecrets(
              exchange, ResourceKind.SECRETMAP, Verb.DELETE, tenantId, Optional.of(name))) {
            handleDeleteSecretMap(exchange, tenantId, name);
          }
        }
        default -> respond(exchange, 405, "method not allowed");
      }
    } catch (IllegalArgumentException | GimleSecretsException e) {
      respondQuietly(exchange, 400, String.valueOf(e.getMessage()));
    } catch (IOException | RuntimeException e) {
      log.warn("secretmaps request failed: {}", e.getMessage());
      respondQuietly(exchange, 500, "internal error");
    } finally {
      exchange.close();
    }
  }

  private void handleListSecretMapNames(HttpExchange exchange, String tenantId) throws IOException {
    respondJson(exchange, 200, Map.of("names", secretMapStore.listNames(tenantId)));
  }

  private void handleGetSecretMapValues(HttpExchange exchange, String tenantId, List<String> names)
      throws IOException {
    Map<String, Map<String, byte[]>> values = secretMapStore.getValues(tenantId, names);
    Map<String, Object> secretMaps = new LinkedHashMap<>();
    for (Map.Entry<String, Map<String, byte[]>> entry : values.entrySet()) {
      Map<String, String> encoded = new LinkedHashMap<>();
      for (Map.Entry<String, byte[]> keyValue : entry.getValue().entrySet()) {
        encoded.put(keyValue.getKey(), encodeBase64(keyValue.getValue()));
      }
      secretMaps.put(entry.getKey(), Map.of("data", encoded));
    }
    respondJson(exchange, 200, Map.of("secretMaps", secretMaps));
  }

  private void handleGetSecretMapMetadata(HttpExchange exchange, String tenantId, String name)
      throws IOException {
    List<Map<String, Object>> keys =
        secretMapStore.getMetadata(tenantId, name).stream()
            .map(FafnirServer::secretMetadataToJson)
            .toList();
    respondJson(exchange, 200, Map.of("name", name, "keys", keys));
  }

  private void handlePutSecretMap(HttpExchange exchange, String tenantId, String name)
      throws IOException {
    Map<String, Object> body = Json.asObject(Json.parse(readBody(exchange)));
    Map<String, Object> rawData = Json.asObject(body.get("data"));
    if (rawData.isEmpty()) {
      respond(exchange, 400, "'data' must be a non-empty mapping of key to base64 value");
      return;
    }
    Map<String, byte[]> values = new LinkedHashMap<>();
    for (Map.Entry<String, Object> entry : rawData.entrySet()) {
      values.put(entry.getKey(), decodeBase64((String) entry.getValue()));
    }
    List<SecretMapStore.SecretMapKeyResult> results =
        secretMapStore.setMany(tenantId, name, values);
    List<Map<String, Object>> resultsJson =
        results.stream().map(FafnirServer::secretMapKeyResultToJson).toList();
    respondJson(exchange, 200, Map.of("results", resultsJson));
  }

  private void handleDeleteSecretMap(HttpExchange exchange, String tenantId, String name)
      throws IOException {
    // Idempotent, matching every other resource kind's own delete-of-a-never-existed-name
    // convention -- see handleDeleteSecret's identical reasoning.
    boolean destroy = "true".equals(parseQuery(exchange).get("destroy"));
    secretMapStore.deleteAll(tenantId, name, destroy);
    respond(exchange, 200, "ok");
  }

  private void handleDeleteSecretMapKey(
      HttpExchange exchange, String tenantId, String name, String key) throws IOException {
    boolean destroy = "true".equals(parseQuery(exchange).get("destroy"));
    secretMapStore.deleteKey(tenantId, name, key, destroy);
    respond(exchange, 200, "ok");
  }

  private void handleListSecretMapGroupVersions(HttpExchange exchange, String tenantId, String name)
      throws IOException {
    List<Map<String, Object>> groupVersions =
        secretMapStore.listGroupVersions(tenantId, name).stream()
            .map(FafnirServer::groupVersionToJson)
            .toList();
    respondJson(exchange, 200, Map.of("groupVersions", groupVersions));
  }

  private void handleRollbackSecretMap(HttpExchange exchange, String tenantId, String name)
      throws IOException {
    Map<String, Object> body = Json.asObject(Json.parse(readBody(exchange)));
    Object raw = body.get("groupVersion");
    if (!(raw instanceof Number number)) {
      respond(exchange, 400, "'groupVersion' must be an integer");
      return;
    }
    SecretMapStore.RollbackOutcome outcome =
        secretMapStore.rollback(tenantId, name, number.intValue());
    switch (outcome) {
      case SecretMapStore.RollbackOutcome.TargetNotFound ignored ->
          respond(
              exchange,
              404,
              "no such group version of SecretMap " + name + ": " + number.intValue());
      case SecretMapStore.RollbackOutcome.Applied applied -> {
        List<Map<String, Object>> resultsJson =
            applied.results().stream().map(FafnirServer::secretMapKeyResultToJson).toList();
        respondJson(
            exchange,
            200,
            Map.of("results", resultsJson, "groupVersion", applied.newGroupVersion()));
      }
    }
  }

  /**
   * {@code POST /secretmaps/{tenantId}/{name}/seal}, body {@code {"sealed": {"<key>":
   * {"sealingKeyId", "wrappedKey", "ciphertext"}, ...}}} -- the "commit"+"apply" half of seal,
   * commit, apply: for each entry, unwraps the data key under the matching sealing private key with
   * an OAEP label bound to this exact {@code (tenantId, name, key)}, then decrypts the payload. Any
   * failure (unknown/retired sealing key id, a label bound to a different tenant/name/key, a
   * corrupt blob) becomes that key's own {@link SecretMapStore.SecretMapKeyResult#failed} entry
   * *without* ever calling {@link SecretMapStore#setMany} for it -- only successfully-recovered
   * plaintexts reach that existing, unchanged write path, which is what gives seal-commit Phase 2's
   * group-versioning for free.
   */
  private void handleSealSecretMap(HttpExchange exchange, String tenantId, String name)
      throws IOException {
    Map<String, Object> body = Json.asObject(Json.parse(readBody(exchange)));
    Map<String, Object> rawSealed = Json.asObject(body.get("sealed"));
    if (rawSealed.isEmpty()) {
      respond(exchange, 400, "'sealed' must be a non-empty mapping of key to sealed envelope");
      return;
    }
    Map<String, byte[]> recovered = new LinkedHashMap<>();
    List<SecretMapStore.SecretMapKeyResult> failures = new ArrayList<>();
    for (Map.Entry<String, Object> entry : rawSealed.entrySet()) {
      String key = entry.getKey();
      try {
        Map<String, Object> envelopeJson = Json.asObject(entry.getValue());
        byte sealingKeyId = parseKeyId(envelopeJson.get("sealingKeyId"));
        SealCipher.SealedEnvelope envelope =
            new SealCipher.SealedEnvelope(
                sealingKeyId,
                decodeBase64((String) envelopeJson.get("wrappedKey")),
                decodeBase64((String) envelopeJson.get("ciphertext")));
        PrivateKey privateKey =
            sealingCrypto
                .privateKeyFor(sealingKeyId)
                .orElseThrow(
                    () ->
                        GimleSecretsException.unknownKeyId(
                            "sealing", Byte.toUnsignedInt(sealingKeyId)));
        byte[] aad = SealCipher.aadFor(tenantId, name, key);
        recovered.put(key, SealCipher.unseal(envelope, privateKey, aad));
      } catch (RuntimeException e) {
        failures.add(
            new SecretMapStore.SecretMapKeyResult(
                key, OptionalInt.empty(), Optional.of(String.valueOf(e.getMessage()))));
      }
    }
    List<SecretMapStore.SecretMapKeyResult> results = new ArrayList<>();
    if (!recovered.isEmpty()) {
      results.addAll(secretMapStore.setMany(tenantId, name, recovered));
    }
    results.addAll(failures);
    List<Map<String, Object>> resultsJson =
        results.stream().map(FafnirServer::secretMapKeyResultToJson).toList();
    respondJson(exchange, 200, Map.of("results", resultsJson));
  }

  private static Map<String, Object> groupVersionToJson(
      SecretMapStore.SecretMapGroupVersion groupVersion) {
    List<Map<String, Object>> keys =
        groupVersion.keys().entrySet().stream()
            .map(
                entry ->
                    Map.<String, Object>of(
                        "key", entry.getKey(),
                        "version", entry.getValue().version(),
                        "deleted", entry.getValue().deleted()))
            .toList();
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("groupVersion", groupVersion.groupVersion());
    map.put("keys", keys);
    groupVersion.rollbackOfGroupVersion().ifPresent(v -> map.put("rollbackOfGroupVersion", v));
    return map;
  }

  private static Map<String, Object> secretMapKeyResultToJson(
      SecretMapStore.SecretMapKeyResult result) {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("key", result.key());
    result.version().ifPresent(version -> map.put("version", version));
    result.error().ifPresent(error -> map.put("error", error));
    return map;
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
   * Fafnir's own, independent authorization decision: never treats "this request arrived
   * already-forwarded by gimle-controlplane" as proof of authorization by itself -- reads RBAC data
   * through its own {@link Authorizer}/{@code StoreClient} and reaches its own conclusion, so a
   * buggy or compromised control-plane replica that forwards an unauthorized principal is still
   * caught here. Same default-plaintext posture as every other Gimlé process: no TLS means no
   * identity to check in the first place, so every request passes -- {@code
   * gimle.transport.protocol=tls} is the one switch that turns this check on, cluster-wide.
   *
   * <p>Also the single point every {@code /secrets/*}/{@code /secretmaps/*} request passes through
   * with its principal, tenant, key, and verb all in hand -- so this is where rate limiting (a
   * {@link #authzThrottle} keyed by principal, incrementing on a denial) and the audit log entry
   * both live, rather than duplicating either concern into every individual handler. {@code kind}
   * is {@link ResourceKind#SECRET} for the flat surface, {@link ResourceKind#SECRETMAP} for the
   * named, grouped one -- the same split {@code ApiServer}'s own {@code CONFIG}/{@code CONFIGMAP}
   * check already establishes.
   */
  private boolean authorizeSecrets(
      HttpExchange exchange, ResourceKind kind, Verb verb, String tenantId, Optional<String> key) {
    if (!(exchange instanceof HttpsExchange)) {
      // Plaintext mode has no identity to check -- fully open, matching the documented design --
      // but the audit trail must still say a secret operation happened rather than showing
      // nothing at all for every request this process ever received in this mode. Attributed to
      // the same synthetic "anonymous" principal the console's own session endpoint already
      // reports for this mode (see handleAuthSession above).
      recordAudit(kind, new Principal("anonymous", Set.of()), verb, tenantId, key, true);
      return true;
    }
    Optional<Principal> resolved = resolvePrincipal(exchange);
    if (resolved.isEmpty()) {
      respondQuietly(exchange, 401, "authentication required");
      return false;
    }
    Principal principal = resolved.get();
    String throttleKey = principal.name();
    Optional<Instant> throttledUntil = authzThrottle.throttledUntil(throttleKey);
    if (throttledUntil.isPresent()) {
      respondThrottled(exchange, throttledUntil.get());
      return false;
    }
    boolean allowed = decideAllowed(kind, principal, verb, tenantId);
    recordAudit(kind, principal, verb, tenantId, key, allowed);
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
   * The RBAC decision itself: a {@code gimle:nodes} principal takes the node-scoped self-service
   * path (see {@link #isTenantAssignedToNode}) rather than the ordinary {@link Authorizer} check,
   * since a node certificate has no {@code Role}/{@code RoleBinding} of its own to look up -- READ
   * only, regardless of {@code kind}, the same restriction whichever surface a node calls.
   */
  private boolean decideAllowed(
      ResourceKind kind, Principal principal, Verb verb, String tenantId) {
    return principal.groups().contains(BuiltinRoles.GROUP_NODES)
        ? verb == Verb.READ && authorizer.isTenantAssignedToNode(principal.name(), tenantId)
        : authorizer.authorize(principal, kind, verb, Optional.of(tenantId), Optional.empty());
  }

  /**
   * Dual audit logging for a completed {@code /secrets/*}/{@code /secretmaps/*} authorization
   * decision: the SLF4J {@code auditLog} line (independent value for an operator tailing this
   * process's own log file, no query needed) plus the durable, queryable {@link AuditEvent}
   * counterpart, giving gimle-observability a general-purpose audit-event-log mechanism it
   * previously lacked.
   */
  private void recordAudit(
      ResourceKind kind,
      Principal principal,
      Verb verb,
      String tenantId,
      Optional<String> key,
      boolean allowed) {
    auditLog.info(
        "principal={} tenant={} key={} verb={} allow={}",
        principal.name(),
        tenantId,
        key.orElse("-"),
        verb,
        allowed);
    crypto
        .storeClient()
        .propose(
            new StateMutation.AppendAuditEvent(
                new AuditEvent(
                    UUID.randomUUID().toString(),
                    principal.name(),
                    principal.groups(),
                    kind.name(),
                    verb.name(),
                    Optional.of(tenantId),
                    key,
                    allowed,
                    System.currentTimeMillis())));
  }

  // isTenantAssignedToNode lives on Authorizer now (gimle-mimir.authz) -- both this class and
  // gimle-controlplane's own /endpoints/* route need the identical node-tenant-scoping check, so
  // it moved to their one shared dependency rather than staying duplicated here.

  /**
   * A forwarded principal (set only by {@code ApiServer}'s own {@code /secrets/*} proxy) wins over
   * the connection's own peer certificate when both are present, since a proxied request's peer
   * certificate identifies the control-plane replica making the call, not the human or node that
   * originated it. Falls back to the peer certificate for a caller reaching Fafnir without going
   * through the proxy at all (a node agent's own direct fetch, or a test simulating one), and
   * finally to Fafnir's own console session cookie -- a human operator signed in through {@link
   * #handleAuthLogin} directly, the one caller shape with neither a forwarded header nor a client
   * certificate of its own.
   */
  private Optional<Principal> resolvePrincipal(HttpExchange exchange) {
    Optional<String> forwardedName = firstHeader(exchange, FORWARDED_PRINCIPAL_HEADER);
    if (forwardedName.isPresent()) {
      Set<String> groups = new LinkedHashSet<>(splitHeader(exchange, FORWARDED_GROUPS_HEADER));
      return Optional.of(new Principal(forwardedName.get(), groups));
    }
    Optional<Principal> certificatePrincipal =
        peerCertificate(exchange).map(Subjects::principalFrom);
    if (certificatePrincipal.isPresent()) {
      return certificatePrincipal;
    }
    return sessionCookie(exchange)
        .flatMap(token -> SessionTokens.verify(token, sessionSigningKey))
        .map(username -> new Principal(username, Set.of()));
  }

  // ---- /auth/login, /auth/logout, /auth/session, /status ----

  /**
   * No {@link #authorizeSecrets}-style check here, deliberately: {@code /auth/login} and {@code
   * /auth/session} must both be reachable with no identity yet -- that's the whole point of a login
   * endpoint, and how the console tells "logged out" apart from "logged in" -- and {@code
   * /auth/logout} only ever clears whatever cookie is presented, authenticated or not. Same
   * plaintext-mode posture as {@link #authorizeSecrets}: a login only ever produces a cookie {@link
   * #resolvePrincipal} would check, and that check is itself a no-op outside TLS, so accepting
   * credentials in plaintext mode too costs nothing beyond what's already true of the whole
   * process.
   */
  private void handleAuthLogin(HttpExchange exchange) {
    try {
      if (!"POST".equals(exchange.getRequestMethod())) {
        respond(exchange, 405, "method not allowed");
        return;
      }
      String addressKey = "addr:" + remoteAddressKey(exchange);
      Optional<Instant> addressThrottled = loginThrottle.throttledUntil(addressKey);
      if (addressThrottled.isPresent()) {
        respondThrottled(exchange, addressThrottled.get());
        return;
      }

      Map<?, ?> body = (Map<?, ?>) Json.parse(readBody(exchange));
      String username = (String) body.get("username");
      String password = (String) body.get("password");
      // Deliberately keyed even for a blank/absent username, rather than skipped -- an attacker
      // probing with no username at all still consumes this key's own backoff, same as a real one.
      String usernameKey = "user:" + (username == null ? "" : username);
      Optional<Instant> usernameThrottled = loginThrottle.throttledUntil(usernameKey);
      if (usernameThrottled.isPresent()) {
        respondThrottled(exchange, usernameThrottled.get());
        return;
      }

      Optional<Account> account =
          username == null ? Optional.empty() : crypto.storeClient().getAccount(username);
      if (account.isEmpty()
          || password == null
          || !PasswordHashes.verify(password.toCharArray(), account.get().passwordHash())) {
        loginThrottle.recordFailure(usernameKey);
        loginThrottle.recordFailure(addressKey);
        // Deliberately the same message either way -- distinguishing "unknown username" from
        // "wrong password" would let this endpoint enumerate valid usernames.
        respondQuietly(exchange, 401, "invalid username or password");
        return;
      }
      loginThrottle.recordSuccess(usernameKey);
      loginThrottle.recordSuccess(addressKey);
      String token = SessionTokens.issue(username, sessionSigningKey, SESSION_TTL);
      exchange
          .getResponseHeaders()
          .add("Set-Cookie", sessionCookieHeader(token, SESSION_TTL.toSeconds()));
      respondJson(exchange, 200, principalToJson(new Principal(username, Set.of()), false));
    } catch (IOException | RuntimeException e) {
      log.warn("login request failed: {}", e.getMessage());
      respondQuietly(exchange, 500, "internal error");
    } finally {
      exchange.close();
    }
  }

  private void handleAuthLogout(HttpExchange exchange) {
    try {
      if (!"POST".equals(exchange.getRequestMethod())) {
        respond(exchange, 405, "method not allowed");
        return;
      }
      exchange.getResponseHeaders().add("Set-Cookie", sessionCookieHeader("", 0));
      respond(exchange, 200, "ok");
    } catch (IOException e) {
      respondQuietly(exchange, 500, "internal error");
    } finally {
      exchange.close();
    }
  }

  /**
   * Polled by the console on load to tell "already logged in" apart from "show the login page".
   *
   * <p>Needs the same plaintext carve-out {@link #authorizeSecrets} already has, but as a fallback
   * behind {@link #resolvePrincipal} rather than ahead of it: a genuine session cookie must still
   * resolve to its real principal regardless of transport -- {@code FafnirServerAuthTest} exercises
   * exactly this login-without-TLS path -- so only the true "nobody's logged in" case gets the
   * plaintext carve-out. Without it, a bare 401 there -- indistinguishable from "on TLS and not
   * logged in" -- would send the console straight to a login form no credential could ever satisfy
   * (plaintext bootstrap never seeds a bootstrap account), locking the operator out entirely rather
   * than leaving the cluster open the way every other endpoint already is in this mode.
   *
   * <p>The synthetic fallback principal is marked {@code anonymous: true} in its JSON, deliberately
   * distinct from a real login's response: the console's login page uses that flag to tell "there's
   * a free pass, nothing to redirect for" apart from "an operator is actually signed in" -- without
   * it, the console treated the free pass itself as a completed login and would redirect away from
   * {@code /login} the instant the page loaded, before a real login ever had a chance to happen.
   */
  private void handleAuthSession(HttpExchange exchange) {
    try {
      if (!"GET".equals(exchange.getRequestMethod())) {
        respond(exchange, 405, "method not allowed");
        return;
      }
      Optional<Principal> principal = resolvePrincipal(exchange);
      if (principal.isPresent()) {
        respondJson(exchange, 200, principalToJson(principal.get(), false));
        return;
      }
      if (!(exchange instanceof HttpsExchange)) {
        // Plaintext mode: no real session, but nothing is actually gated behind one either (see
        // authorizeSecrets's own carve-out) -- report an anonymous session rather than 401, so the
        // console doesn't force a login screen that, with no bootstrap account seeded in plaintext
        // mode, may not be satisfiable by any credential at all. A genuine login (see the branch
        // above) still takes priority whenever a valid cookie is actually presented.
        respondJson(exchange, 200, principalToJson(new Principal("anonymous", Set.of()), true));
        return;
      }
      respondQuietly(exchange, 401, "not authenticated");
    } catch (IOException e) {
      respondQuietly(exchange, 500, "internal error");
    } finally {
      exchange.close();
    }
  }

  /**
   * The console's Overview screen: no {@link #authorizeSecrets}-style RBAC gate -- nothing here is
   * per-tenant or secret-value-bearing (never a value, never a key name), just process-level status
   * every operator who can reach this port is already trusted to see, the same posture {@code
   * ApiServer}'s own unauthenticated-in-plaintext-mode baseline already takes for its own read-only
   * status surfaces.
   */
  private void handleStatus(HttpExchange exchange) {
    try {
      if (!"GET".equals(exchange.getRequestMethod())) {
        respond(exchange, 405, "method not allowed");
        return;
      }
      Map<String, Object> status = new LinkedHashMap<>();
      status.put("uptimeSeconds", Duration.between(startedAt, Instant.now()).toSeconds());
      status.put("activeKeyId", Byte.toUnsignedInt(crypto.activeKeyId()));
      // Never the key material itself, only its fingerprint -- with no peer-discovery mechanism
      // of its own to compare this automatically across replicas, this is what lets an operator
      // manually diff /status output across every replica and notice a silently drifted key ring.
      status.put("secretsKeyRingFingerprint", crypto.keyRingFingerprint());
      status.put("transportProtocol", TransportProtocol.fromConfig().name());
      status.put(
          "tenants", crypto.storeClient().listTenants().stream().map(Tenant::id).sorted().toList());
      respondJson(exchange, 200, status);
    } catch (IOException | RuntimeException e) {
      log.warn("status request failed: {}", e.getMessage());
      respondQuietly(exchange, 500, "internal error");
    } finally {
      exchange.close();
    }
  }

  private static Map<String, Object> principalToJson(Principal principal, boolean anonymous) {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("username", principal.name());
    map.put("groups", List.copyOf(principal.groups()));
    map.put("anonymous", anonymous);
    return map;
  }

  /**
   * The connection's own remote address, never a client-supplied header like {@code
   * X-Forwarded-For} -- this class has no configured notion of a trusted reverse proxy, so honoring
   * a client-set header here would let an attacker defeat the address-keyed throttle entirely by
   * sending a different forged value on every request. Falls back to a fixed placeholder if
   * unavailable (never null, so it's always safe to use as a map key), which under-differentiates
   * callers in that rare case rather than throwing.
   */
  private static String remoteAddressKey(HttpExchange exchange) {
    InetSocketAddress remote = exchange.getRemoteAddress();
    return remote == null ? "unknown" : remote.getAddress().getHostAddress();
  }

  private static Optional<String> sessionCookie(HttpExchange exchange) {
    List<String> cookieHeaders = exchange.getRequestHeaders().get("Cookie");
    if (cookieHeaders == null) {
      return Optional.empty();
    }
    String prefix = SESSION_COOKIE_NAME + "=";
    for (String header : cookieHeaders) {
      for (String part : header.split(";")) {
        String trimmed = part.trim();
        if (trimmed.startsWith(prefix)) {
          return Optional.of(trimmed.substring(prefix.length()));
        }
      }
    }
    return Optional.empty();
  }

  /**
   * {@code HttpOnly} (never readable by the console's own JS -- an XSS in the SPA can't exfiltrate
   * it), {@code SameSite=Strict} (never attached to a request originating from another site -- a
   * CSRF mitigation, since auth here is cookie- not header-based), {@code Secure} only in TLS mode
   * (a plaintext connection can't set a cookie the browser would ever actually send back over
   * plaintext anyway). {@code maxAgeSeconds} of {@code 0} is how {@link #handleAuthLogout} clears
   * it.
   */
  private static String sessionCookieHeader(String token, long maxAgeSeconds) {
    StringBuilder header =
        new StringBuilder(SESSION_COOKIE_NAME)
            .append('=')
            .append(token)
            .append("; Path=/; HttpOnly; SameSite=Strict; Max-Age=")
            .append(maxAgeSeconds);
    if (TransportProtocol.fromConfig() == TransportProtocol.TLS) {
      header.append("; Secure");
    }
    return header.toString();
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
    HttpResponses.respond(exchange, status, body);
  }

  private static void respondJson(HttpExchange exchange, int status, Object value)
      throws IOException {
    HttpResponses.respondJson(exchange, status, value);
  }

  private static void respondQuietly(HttpExchange exchange, int status, String body) {
    HttpResponses.respondQuietly(exchange, status, body);
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
