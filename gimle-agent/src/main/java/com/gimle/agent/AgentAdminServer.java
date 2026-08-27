package com.gimle.agent;

import com.gimle.core.authz.Principal;
import com.gimle.core.authz.ResourceKind;
import com.gimle.core.authz.Verb;
import com.gimle.core.protocol.AuditEvent;
import com.gimle.core.protocol.Json;
import com.gimle.core.throttle.LoginThrottle;
import com.gimle.core.tls.SslContexts;
import com.gimle.core.tls.TlsSettings;
import com.gimle.core.tls.TransportProtocol;
import com.gimle.core.web.HttpResponses;
import com.gimle.mimir.authz.Authorizer;
import com.gimle.mimir.raft.StateMutation;
import com.gimle.mimir.rpc.StoreClient;
import com.gimle.pki.Subjects;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsExchange;
import com.sun.net.httpserver.HttpsParameters;
import com.sun.net.httpserver.HttpsServer;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLPeerUnverifiedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The node agent's Admin Fault API: a small, independently-authorized HTTP surface letting Ragnarök
 * trigger {@code WORKER_KILL} directly against this agent, without needing SSH access to the
 * machine at all -- a third {@code ClusterTarget} trust model alongside the plaintext-only {@code
 * EndpointClusterTarget} and the SSH-backed {@code SshInventoryClusterTarget}. Opt-in: only
 * constructed by {@code AgentMain} when {@code -Dgimle.agent.storeEndpoints} is configured, so an
 * agent whose operator never uses this feature never opens the port at all.
 *
 * <p>Deliberately scoped to {@code WORKER_KILL} only -- the one fault kind whose "bring it back"
 * logic already lives entirely inside this agent ({@code WorkerProcessSupervisor}'s own {@code
 * onExit}-driven respawn, wired through {@code AgentMain#onWorkerCrash}); every other bounce fault
 * kind needs the calling tool itself to restart the victim after a dwell, which this agent has no
 * relationship to for any process kind it doesn't supervise (store/control-plane/Fafnir/Muninn/
 * Andvari).
 *
 * <p>Force-kills the underlying OS {@link Process} directly, leaving the {@link SupervisedInstance}
 * entry in {@code supervised} untouched -- exactly what {@code AgentMain}'s own crash-detection
 * path already treats as an unexpected exit, so respawn, catalog eviction/redrive, and {@code
 * RestartTracker} backoff all happen through that existing path with no new bookkeeping here. This
 * is deliberately not {@code AgentMain#stopInstance}: that method permanently deregisters an
 * instance for graceful undeploy, which would make a killed worker vanish instead of respawn.
 *
 * <p>Auth mirrors Fafnir/Andvari/Muninn's defense-in-depth pattern -- an independent {@link
 * Authorizer} re-check against this agent's own {@link StoreClient}, never trusting the caller's
 * identity as proof by itself -- but deliberately narrower than their own three-tier {@code
 * resolvePrincipal}: no forwarded-principal header (Ragnarök dials this agent directly, never
 * through the control plane, so there is no already-mTLS-authenticated proxy hop to trust a
 * forwarded claim from) and no session-cookie fallback (this listener has no login/console
 * concept). Peer certificate only -- the narrower trust boundary is the point of this feature.
 */
final class AgentAdminServer implements AutoCloseable {

  private static final Logger log = LoggerFactory.getLogger(AgentAdminServer.class);
  private static final Logger auditLog = LoggerFactory.getLogger("com.gimle.agent.audit");

  /**
   * Same allow-list {@code AgentLogServer.DEPLOYMENT_NAME} already uses, for the identical
   * path-traversal-prevention reason -- deployment names elsewhere in the system carry no charset
   * restriction, so this is enforced here rather than assumed.
   */
  private static final Pattern DEPLOYMENT_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");

  private final StoreClient storeClient;
  private final Authorizer authorizer;
  private final Map<String, SupervisedInstance> supervised;
  private final LoginThrottle authzThrottle = new LoginThrottle();
  private final int boundPort;
  private volatile HttpServer server;

  AgentAdminServer(StoreClient storeClient, int port, Map<String, SupervisedInstance> supervised)
      throws IOException {
    this.storeClient = storeClient;
    this.authorizer = new Authorizer(storeClient);
    this.supervised = supervised;
    this.server = createHttpServer(port);
    this.boundPort = server.getAddress().getPort();
    registerContexts(server);
    server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
  }

  /** Same plaintext/mTLS toggle every other Gimlé process kind's own HTTP server already uses. */
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
            // Must go through setSSLParameters(...), not params.setWantClientAuth(...) directly --
            // same ordering requirement every other createHttpServer here already documents.
            SSLParameters sslParameters = getSSLContext().getDefaultSSLParameters();
            sslParameters.setWantClientAuth(true);
            params.setSSLParameters(sslParameters);
          }
        });
    return httpsServer;
  }

  private void registerContexts(HttpServer target) {
    // One context dispatching on the parsed tail, matching AndvariServer's own "/artifacts"
    // convention -- the JDK server matches by bare prefix, so a single handler owning the whole
    // subtree keeps "/admin/faults/workersanything" from ever matching this route.
    target.createContext("/admin/faults/workers/", this::handleWorkers);
  }

  void start() {
    server.start();
  }

  int port() {
    return boundPort;
  }

  @Override
  public void close() {
    server.stop(0);
  }

  /**
   * Rebuilds the {@link HttpServer}/{@link HttpsServer} from whatever TLS material now sits at
   * {@code gimle.tls.certFile}/{@code keyFile} -- the same stop/rebuild/re-register/restart
   * contract every other Gimlé process kind's identical method already establishes (no hot-swap is
   * possible with the JDK server).
   */
  synchronized void reloadTlsMaterial() throws IOException {
    if (TransportProtocol.fromConfig() == TransportProtocol.PLAINTEXT) {
      return;
    }
    server.stop(0);
    HttpServer rebuilt = createHttpServer(boundPort);
    registerContexts(rebuilt);
    rebuilt.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
    rebuilt.start();
    server = rebuilt;
    log.info("reloaded TLS material and rebuilt admin fault API HttpsServer on port {}", boundPort);
  }

  // ---- GET/POST /admin/faults/workers/{deploymentName}/{instanceIndex}[/kill] ----

  private void handleWorkers(HttpExchange exchange) {
    try {
      String tail = exchange.getRequestURI().getPath().substring("/admin/faults/workers/".length());
      String[] parts = tail.split("/", 3);
      if (parts.length < 2 || !DEPLOYMENT_NAME.matcher(parts[0]).matches()) {
        respond(
            exchange,
            400,
            "expected /admin/faults/workers/{deploymentName}/{instanceIndex}[/kill]");
        return;
      }
      String deploymentName = parts[0];
      int instanceIndex;
      try {
        instanceIndex = Integer.parseInt(parts[1]);
      } catch (NumberFormatException e) {
        respond(exchange, 400, "invalid instanceIndex");
        return;
      }
      boolean killRequest = parts.length == 3 && "kill".equals(parts[2]);
      if (killRequest && !"POST".equals(exchange.getRequestMethod())) {
        respond(exchange, 405, "method not allowed");
        return;
      }
      if (!killRequest && !"GET".equals(exchange.getRequestMethod())) {
        respond(exchange, 405, "method not allowed");
        return;
      }

      String key = deploymentName + "#" + instanceIndex;
      SupervisedInstance instance = supervised.get(key);
      // Authorize before ever revealing whether this instance exists here at all -- an unknown
      // key still needs a real grant to get past this check, using whatever tenant is known (empty
      // when the instance is unsupervised, matching how a cluster-admin/operator grant, which needs
      // no tenant, is still enough to pass).
      Optional<String> tenantId =
          instance == null ? Optional.empty() : instance.assigned.tenantId();
      if (!authorizeFault(
          exchange, killRequest ? Verb.WRITE : Verb.READ, deploymentName, tenantId)) {
        return;
      }
      if (instance == null) {
        respond(exchange, 404, "no supervised instance for " + key + " on this node");
        return;
      }
      if (killRequest) {
        handleKill(exchange, instance, key);
      } else {
        handleStatus(exchange, instance);
      }
    } catch (IOException | RuntimeException e) {
      log.warn("admin fault API request failed: {}", e.getMessage());
      respondQuietly(exchange, 500, "internal error");
    } finally {
      exchange.close();
    }
  }

  private static void handleStatus(HttpExchange exchange, SupervisedInstance instance)
      throws IOException {
    Process process = instance.supervisor.process();
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("pid", process.pid());
    body.put("alive", process.isAlive());
    respondJson(exchange, 200, body);
  }

  /**
   * Pid-targeted, not "whatever's currently supervised" -- mirrors {@code SshWorkerHandle.kill()}'s
   * own semantics over SSH: a caller resolved a worker's pid via a prior {@code GET}, and this call
   * only kills that exact process, refusing (409, not silently killing a different one) if a
   * respawn already replaced it since -- protects against a chaos tool racing its own strike
   * against a legitimate respawn that happened moments earlier.
   */
  private static void handleKill(HttpExchange exchange, SupervisedInstance instance, String key)
      throws IOException {
    Map<String, Object> requestBody = Json.asObject(Json.parse(readBody(exchange)));
    Object pidValue = requestBody.get("pid");
    if (!(pidValue instanceof Number)) {
      respond(exchange, 400, "expected a JSON body {\"pid\": <long>}");
      return;
    }
    long requestedPid = ((Number) pidValue).longValue();
    Process process = instance.supervisor.process();
    long currentPid = process.pid();
    if (requestedPid != currentPid) {
      respondJson(
          exchange,
          409,
          Map.of("killed", false, "reason", "pid mismatch, currently " + currentPid));
      return;
    }
    process.destroyForcibly();
    log.info("admin fault API killed worker for {} (pid {})", key, currentPid);
    respondJson(exchange, 200, Map.of("killed", true));
  }

  // ---- auth ----

  private boolean authorizeFault(
      HttpExchange exchange, Verb verb, String deploymentName, Optional<String> tenantId)
      throws IOException {
    if (!(exchange instanceof HttpsExchange)) {
      recordAudit(
          new Principal("anonymous", java.util.Set.of()), verb, deploymentName, tenantId, true);
      return true;
    }
    Optional<Principal> resolved = resolvePeerCertificatePrincipal(exchange);
    if (resolved.isEmpty()) {
      respond(exchange, 401, "authentication required");
      return false;
    }
    Principal principal = resolved.get();
    Optional<java.time.Instant> throttledUntil = authzThrottle.throttledUntil(principal.name());
    if (throttledUntil.isPresent()) {
      respondQuietly(exchange, 429, "too many recent failures, try again later");
      return false;
    }
    boolean allowed =
        authorizer.authorize(
            principal, ResourceKind.FAULT, verb, tenantId, Optional.of(deploymentName));
    recordAudit(principal, verb, deploymentName, tenantId, allowed);
    if (!allowed) {
      authzThrottle.recordFailure(principal.name());
      respond(exchange, 403, "forbidden");
      return false;
    }
    authzThrottle.recordSuccess(principal.name());
    return true;
  }

  /** Peer certificate only -- see this class's own javadoc for why, unlike Fafnir/Andvari. */
  private static Optional<Principal> resolvePeerCertificatePrincipal(HttpExchange exchange) {
    if (!(exchange instanceof HttpsExchange httpsExchange)) {
      return Optional.empty();
    }
    try {
      Certificate[] certificates = httpsExchange.getSSLSession().getPeerCertificates();
      if (certificates.length == 0 || !(certificates[0] instanceof X509Certificate leaf)) {
        return Optional.empty();
      }
      return Optional.of(Subjects.principalFrom(leaf));
    } catch (SSLPeerUnverifiedException | RuntimeException e) {
      return Optional.empty();
    }
  }

  /**
   * Dual audit: the tailable SLF4J line plus the durable event, matching every other server here.
   */
  private void recordAudit(
      Principal principal,
      Verb verb,
      String deploymentName,
      Optional<String> tenantId,
      boolean allowed) {
    auditLog.info(
        "principal={} target={} verb={} allow={}", principal.name(), deploymentName, verb, allowed);
    storeClient.propose(
        new StateMutation.AppendAuditEvent(
            new AuditEvent(
                UUID.randomUUID().toString(),
                principal.name(),
                principal.groups(),
                ResourceKind.FAULT.name(),
                verb.name(),
                tenantId,
                Optional.of(deploymentName),
                allowed,
                System.currentTimeMillis())));
  }

  private static String readBody(HttpExchange exchange) throws IOException {
    try (InputStream body = exchange.getRequestBody()) {
      return new String(body.readAllBytes(), StandardCharsets.UTF_8);
    }
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
}
