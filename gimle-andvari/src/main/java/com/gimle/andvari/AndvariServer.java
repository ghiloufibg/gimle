package com.gimle.andvari;

import com.gimle.andvari.ArtifactStore.PutOutcome;
import com.gimle.andvari.ArtifactStore.PutResult;
import com.gimle.andvari.ArtifactStore.StoredArtifact;
import com.gimle.core.authz.BuiltinRoles;
import com.gimle.core.authz.Principal;
import com.gimle.core.authz.ResourceKind;
import com.gimle.core.authz.Verb;
import com.gimle.core.module.ModuleId;
import com.gimle.core.protocol.AuditEvent;
import com.gimle.core.protocol.Json;
import com.gimle.core.tls.SslContexts;
import com.gimle.core.tls.TlsSettings;
import com.gimle.core.tls.TransportProtocol;
import com.gimle.mimir.authz.Authorizer;
import com.gimle.mimir.manifest.DeploymentSpec;
import com.gimle.mimir.raft.StateMutation;
import com.gimle.mimir.rpc.StoreClient;
import com.gimle.mimir.store.DaemonSetAssignment;
import com.gimle.mimir.store.InstanceAssignment;
import com.gimle.mimir.store.JobRun;
import com.gimle.mimir.store.StatefulSetAssignment;
import com.gimle.pki.Subjects;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsExchange;
import com.sun.net.httpserver.HttpsParameters;
import com.sun.net.httpserver.HttpsServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLPeerUnverifiedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Andvari's HTTP surface: {@code com.sun.net.httpserver.HttpServer}, JDK-bundled, no framework
 * dependency -- the same minimal stack {@code ApiServer}/{@code FafnirServer}/{@code MuninnServer}
 * already use. One operational REST surface over the {@link ArtifactStore}:
 *
 * <pre>
 * GET            /artifacts                        catalog of module ids
 * GET            /artifacts/{moduleId}             stored versions with checksums/provenance
 * HEAD/GET       /artifacts/{moduleId}/{version}   digest headers / raw jar bytes
 * PUT            /artifacts/{moduleId}/{version}   upload (raw jar body); differing re-push is 409
 * DELETE         /artifacts/{moduleId}/{version}   operator-driven removal
 * </pre>
 *
 * <p>A second, Maven-2-shaped view over the identical store lives under {@code /repository/**} --
 * see {@link #handleRepository} for the path translation and what a stock {@code mvn deploy}
 * actually needs from it.
 *
 * <p>Authorization is the defense-in-depth posture {@code FafnirServer} established: plaintext mode
 * is open like every other Gimlé surface; under mTLS a forwarded principal (set only by {@code
 * ApiServer}'s proxy) wins over the peer certificate, and this process re-runs its own independent
 * {@link Authorizer#authorize} regardless -- never trusting "arrived already-forwarded" as proof by
 * itself. A {@code gimle:nodes} principal may only ever pull -- never push or delete -- and only a
 * coordinate its node currently holds an assignment for (see {@link #nodeHasAssignmentFor}).
 */
public final class AndvariServer implements AutoCloseable {

  private static final Logger log = LoggerFactory.getLogger(AndvariServer.class);
  private static final Logger auditLog = LoggerFactory.getLogger("com.gimle.andvari.audit");

  private static final String FORWARDED_PRINCIPAL_HEADER = "X-Gimle-Forwarded-Principal";
  private static final String FORWARDED_GROUPS_HEADER = "X-Gimle-Forwarded-Groups";
  private static final String SHA256_HEADER = "X-Gimle-Artifact-Sha256";
  private static final String MAVEN_METADATA_FILE = "maven-metadata.xml";
  private static final DateTimeFormatter MAVEN_TIMESTAMP =
      DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(ZoneOffset.UTC);

  private final StoreClient storeClient;
  private final Authorizer authorizer;
  private final ArtifactStore artifactStore;
  private final Instant startedAt = Instant.now();
  // Not final: a TLS rotation rebuilds this the same way FafnirServer's own #server field does --
  // see that class's field javadoc for why a rebuild, not a hot-swap, is the only supported path.
  private volatile HttpServer server;
  private final int boundPort;

  public AndvariServer(StoreClient storeClient, int port, Path dataRoot) throws IOException {
    this.storeClient = storeClient;
    this.authorizer = new Authorizer(storeClient);
    this.artifactStore = new ArtifactStore(dataRoot);
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
            // Same ordering requirement as ApiServer/FafnirServer/MuninnServer's own
            // createHttpServer: setSSLParameters(...) copies its argument's wantClientAuth onto
            // params, so this must run through that call, not params.setWantClientAuth(...).
            SSLParameters sslParameters = getSSLContext().getDefaultSSLParameters();
            sslParameters.setWantClientAuth(true);
            params.setSSLParameters(sslParameters);
          }
        });
    return httpsServer;
  }

  private void registerContexts(HttpServer target) {
    target.createContext("/status", this::handleStatus);
    // One context dispatching on the parsed tail rather than separate "/artifacts" and
    // "/artifacts/" registrations: the JDK server matches contexts by bare prefix, so a single
    // handler owning the whole subtree keeps "/artifactsanything" from ever matching a route.
    target.createContext("/artifacts", this::handleArtifacts);
    // The Maven-2-shaped interop view over the same store -- see handleRepository's own javadoc.
    target.createContext("/repository", this::handleRepository);
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
   * contract {@code FafnirServer.reloadTlsMaterial} established (see that method's javadoc for why
   * a rebuild, not a hot-swap, is the only path the JDK actually supports).
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

  /**
   * Process-level status, no RBAC gate -- nothing here is data-bearing (never a jar's bytes), the
   * same posture {@code FafnirServer}/{@code MuninnServer} take for their own status surfaces.
   */
  private void handleStatus(HttpExchange exchange) {
    try {
      if (!"GET".equals(exchange.getRequestMethod())) {
        respond(exchange, 405, "method not allowed");
        return;
      }
      Map<String, Object> status = new LinkedHashMap<>();
      status.put("uptimeSeconds", Duration.between(startedAt, Instant.now()).toSeconds());
      status.put("transportProtocol", TransportProtocol.fromConfig().name());
      status.put("moduleCount", artifactStore.moduleIds().size());
      respondJson(exchange, 200, status);
    } catch (IOException e) {
      log.warn("status request failed: {}", e.getMessage());
      respondQuietly(exchange, 500, "internal error");
    } finally {
      exchange.close();
    }
  }

  private void handleArtifacts(HttpExchange exchange) {
    try {
      String tail = exchange.getRequestURI().getPath().substring("/artifacts".length());
      if (tail.isEmpty() || "/".equals(tail)) {
        handleCatalog(exchange);
        return;
      }
      if (!tail.startsWith("/")) {
        respond(exchange, 404, "not found");
        return;
      }
      String[] segments = tail.substring(1).split("/");
      if (segments.length == 1) {
        handleVersions(exchange, segments[0]);
        return;
      }
      if (segments.length == 2) {
        handleVersion(exchange, segments[0], segments[1]);
        return;
      }
      respond(exchange, 404, "expected /artifacts/{moduleId}[/{version}]");
    } catch (IllegalArgumentException e) {
      respondQuietly(exchange, 400, String.valueOf(e.getMessage()));
    } catch (IOException | RuntimeException e) {
      log.warn("artifact request failed: {}", e.getMessage());
      respondQuietly(exchange, 500, "internal error");
    } finally {
      exchange.close();
    }
  }

  // ---- GET /artifacts ----

  private void handleCatalog(HttpExchange exchange) throws IOException {
    if (!"GET".equals(exchange.getRequestMethod())) {
      respond(exchange, 405, "method not allowed");
      return;
    }
    if (!authorizeArtifacts(exchange, Verb.READ, "catalog", null, null)) {
      return;
    }
    respondJson(exchange, 200, artifactStore.moduleIds());
  }

  // ---- GET /artifacts/{moduleId} ----

  private void handleVersions(HttpExchange exchange, String moduleId) throws IOException {
    if (!"GET".equals(exchange.getRequestMethod())) {
      respond(exchange, 405, "method not allowed");
      return;
    }
    if (!authorizeArtifacts(exchange, Verb.READ, moduleId, null, null)) {
      return;
    }
    List<StoredArtifact> versions = artifactStore.versions(moduleId);
    if (versions.isEmpty()) {
      respond(exchange, 404, "no versions stored for " + moduleId);
      return;
    }
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("moduleId", moduleId);
    List<Map<String, Object>> versionEntries = new ArrayList<>();
    for (StoredArtifact stored : versions) {
      versionEntries.add(versionJson(stored));
    }
    body.put("versions", versionEntries);
    respondJson(exchange, 200, body);
  }

  // ---- HEAD/GET/PUT/DELETE /artifacts/{moduleId}/{version} ----

  private void handleVersion(HttpExchange exchange, String moduleId, String version)
      throws IOException {
    switch (exchange.getRequestMethod()) {
      case "HEAD" -> handleHead(exchange, moduleId, version);
      case "GET" -> handleDownload(exchange, moduleId, version);
      case "PUT" -> handleUpload(exchange, moduleId, version);
      case "DELETE" -> handleDelete(exchange, moduleId, version);
      default -> respond(exchange, 405, "method not allowed");
    }
  }

  /**
   * The manifest-check equivalent an agent runs before a real download: existence plus digest, no
   * body. The digest travels in {@value #SHA256_HEADER} rather than the body since HEAD has none.
   */
  private void handleHead(HttpExchange exchange, String moduleId, String version)
      throws IOException {
    if (!authorizeArtifacts(exchange, Verb.READ, moduleId + ":" + version, moduleId, version)) {
      return;
    }
    Optional<StoredArtifact> meta = artifactStore.meta(moduleId, version);
    if (meta.isEmpty()) {
      exchange.sendResponseHeaders(404, -1);
      return;
    }
    exchange.getResponseHeaders().add(SHA256_HEADER, meta.get().sha256());
    exchange.getResponseHeaders().add("Content-Type", "application/java-archive");
    exchange.sendResponseHeaders(200, -1);
  }

  private void handleDownload(HttpExchange exchange, String moduleId, String version)
      throws IOException {
    if (!authorizeArtifacts(exchange, Verb.READ, moduleId + ":" + version, moduleId, version)) {
      return;
    }
    Optional<StoredArtifact> meta = artifactStore.meta(moduleId, version);
    Optional<Path> jar = artifactStore.jarPath(moduleId, version);
    if (meta.isEmpty() || jar.isEmpty()) {
      respond(exchange, 404, "no artifact stored for " + moduleId + ":" + version);
      return;
    }
    exchange.getResponseHeaders().add(SHA256_HEADER, meta.get().sha256());
    exchange.getResponseHeaders().add("Content-Type", "application/java-archive");
    exchange.sendResponseHeaders(200, meta.get().sizeBytes());
    // Streamed straight from disk to the socket -- a jar is never buffered whole in memory on
    // either the push or the pull side of this process.
    try (OutputStream out = exchange.getResponseBody()) {
      Files.copy(jar.get(), out);
    }
  }

  private void handleUpload(HttpExchange exchange, String moduleId, String version)
      throws IOException {
    if (!authorizeArtifacts(exchange, Verb.WRITE, moduleId + ":" + version, moduleId, version)) {
      return;
    }
    String pushedBy = resolvePrincipal(exchange).map(Principal::name).orElse("anonymous");
    PutResult result;
    try (var body = exchange.getRequestBody()) {
      result = artifactStore.put(moduleId, version, body, pushedBy);
    }
    if (result.outcome() == PutOutcome.CONFLICT) {
      respond(
          exchange,
          409,
          "artifact "
              + moduleId
              + ":"
              + version
              + " already exists with sha256 "
              + result.stored().sha256()
              + " -- a stored version is immutable; push the changed jar as a new version");
      return;
    }
    Map<String, Object> body = versionJson(result.stored());
    body.put("created", result.outcome() == PutOutcome.CREATED);
    respondJson(exchange, 200, body);
  }

  private void handleDelete(HttpExchange exchange, String moduleId, String version)
      throws IOException {
    if (!authorizeArtifacts(exchange, Verb.DELETE, moduleId + ":" + version, moduleId, version)) {
      return;
    }
    if (!artifactStore.delete(moduleId, version)) {
      respond(exchange, 404, "no artifact stored for " + moduleId + ":" + version);
      return;
    }
    respondJson(exchange, 200, Map.of("deleted", true));
  }

  // ---- Maven-2 repository surface: /repository/{group/path}/{artifactId}/{version}/{file} ----

  /**
   * Translates a Maven-2 repository path into the same {@code (moduleId, version)} coordinate
   * {@code /artifacts/*} uses, per {@link MavenCoordinates}, so the store stays canonical and both
   * surfaces are just path translations over it -- immutability enforced once, at {@link
   * ArtifactStore#put}, can't be bypassed by choosing the other door. Three shapes are recognized:
   *
   * <pre>
   * GET/PUT/DELETE  .../{artifactId}/{version}/{artifactId}-{version}.jar   the module jar itself,
   *                                                                         handled identically to
   *                                                                         /artifacts/{moduleId}/{version}
   * GET             .../{artifactId}/{version}/{artifactId}-{version}.jar.sha256   server-computed digest
   * GET/PUT         anything else under a version directory (.pom, client checksum
   *                 sidecars) -- accepted and stored opaquely, never parsed or trusted
   * GET             .../{artifactId}/maven-metadata.xml[.sha256]           generated from the
   *                                                                         stored version list
   * </pre>
   *
   * A client-uploaded {@code .jar.sha256} is stored like any other sidecar but never read back on
   * this path -- a GET of that exact name always re-derives the digest from {@link
   * ArtifactStore#meta}, so a tampered or stale upload can never be served as the real checksum.
   */
  private void handleRepository(HttpExchange exchange) {
    try {
      String tail = exchange.getRequestURI().getPath().substring("/repository".length());
      if (!tail.startsWith("/") || tail.length() == 1) {
        respond(exchange, 404, "expected /repository/{group/path}/{artifactId}/{version}/{file}");
        return;
      }
      String path = tail.substring(1);
      MavenCoordinates.MetadataFile metadataFile = MavenCoordinates.parseMetadataPath(path);
      if (metadataFile != null) {
        handleRepositoryMetadata(exchange, metadataFile);
        return;
      }
      MavenCoordinates.ArtifactFile artifactFile = MavenCoordinates.parseArtifactFile(path);
      if (artifactFile == null) {
        respond(exchange, 404, "expected /repository/{group/path}/{artifactId}/{version}/{file}");
        return;
      }
      handleRepositoryFile(exchange, artifactFile);
    } catch (IllegalArgumentException e) {
      respondQuietly(exchange, 400, String.valueOf(e.getMessage()));
    } catch (IOException | RuntimeException e) {
      log.warn("maven repository request failed: {}", e.getMessage());
      respondQuietly(exchange, 500, "internal error");
    } finally {
      exchange.close();
    }
  }

  private void handleRepositoryFile(HttpExchange exchange, MavenCoordinates.ArtifactFile file)
      throws IOException {
    String moduleId = file.moduleId();
    String version = file.version();
    String target = moduleId + ":" + version;
    String jarFileName = file.artifactId() + "-" + version + ".jar";
    if (file.fileName().equals(jarFileName)) {
      // Identical handling to the operational surface's own /artifacts/{moduleId}/{version}
      // route -- one code path for the jar itself, reached from two URL shapes.
      handleVersion(exchange, moduleId, version);
      return;
    }
    if (file.fileName().equals(jarFileName + ".sha256")
        && "GET".equals(exchange.getRequestMethod())) {
      handleRepositoryChecksum(exchange, moduleId, version, target);
      return;
    }
    handleRepositorySidecar(exchange, moduleId, version, file.fileName(), target);
  }

  private void handleRepositoryChecksum(
      HttpExchange exchange, String moduleId, String version, String target) throws IOException {
    if (!authorizeArtifacts(exchange, Verb.READ, target, moduleId, version)) {
      return;
    }
    Optional<StoredArtifact> meta = artifactStore.meta(moduleId, version);
    if (meta.isEmpty()) {
      respond(exchange, 404, "no artifact stored for " + target);
      return;
    }
    respond(exchange, 200, meta.get().sha256());
  }

  private void handleRepositorySidecar(
      HttpExchange exchange, String moduleId, String version, String fileName, String target)
      throws IOException {
    switch (exchange.getRequestMethod()) {
      case "GET" -> {
        if (!authorizeArtifacts(exchange, Verb.READ, target, moduleId, version)) {
          return;
        }
        Optional<byte[]> sidecar = artifactStore.sidecar(moduleId, version, fileName);
        if (sidecar.isEmpty()) {
          respond(exchange, 404, "no " + fileName + " stored for " + target);
          return;
        }
        respondBytes(exchange, 200, sidecarContentType(fileName), sidecar.get());
      }
      case "PUT" -> {
        if (!authorizeArtifacts(exchange, Verb.WRITE, target, moduleId, version)) {
          return;
        }
        try (var body = exchange.getRequestBody()) {
          artifactStore.putSidecar(moduleId, version, fileName, body);
        }
        respond(exchange, 200, "stored");
      }
      default -> respond(exchange, 405, "method not allowed");
    }
  }

  private static String sidecarContentType(String fileName) {
    return fileName.endsWith(".pom")
        ? "application/xml; charset=utf-8"
        : "text/plain; charset=utf-8";
  }

  private void handleRepositoryMetadata(
      HttpExchange exchange, MavenCoordinates.MetadataFile metadataFile) throws IOException {
    if (!"GET".equals(exchange.getRequestMethod())) {
      respond(exchange, 405, "method not allowed");
      return;
    }
    if (!authorizeArtifacts(exchange, Verb.READ, metadataFile.moduleId(), null, null)) {
      return;
    }
    List<StoredArtifact> versions = artifactStore.versions(metadataFile.moduleId());
    if (versions.isEmpty()) {
      respond(exchange, 404, "no versions stored for " + metadataFile.moduleId());
      return;
    }
    byte[] xml = generateMavenMetadataXml(metadataFile, versions).getBytes(StandardCharsets.UTF_8);
    if (metadataFile.fileName().equals(MAVEN_METADATA_FILE)) {
      respondBytes(exchange, 200, "application/xml; charset=utf-8", xml);
      return;
    }
    if (metadataFile.fileName().equals(MAVEN_METADATA_FILE + ".sha256")) {
      respond(exchange, 200, sha256Hex(xml));
      return;
    }
    respond(exchange, 404, "unsupported metadata file: " + metadataFile.fileName());
  }

  /**
   * {@code maven-metadata.xml} is always generated fresh from {@link ArtifactStore#versions} --
   * never stored as an uploaded file -- so a stale or hand-edited version list can never hide a
   * real version from a resolving Maven client; the one place a dumb store must not be dumb.
   * Versions are listed in {@link ArtifactStore#versions}'s own (lexicographic) order, matching
   * every other version listing this process serves rather than inventing a second ordering here.
   */
  private static String generateMavenMetadataXml(
      MavenCoordinates.MetadataFile metadataFile, List<StoredArtifact> versions) {
    String latest = versions.get(versions.size() - 1).version();
    long lastUpdatedEpochMilli =
        versions.stream().mapToLong(StoredArtifact::pushedAtEpochMilli).max().orElse(0L);
    StringBuilder xml = new StringBuilder();
    xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
    xml.append("<metadata>\n");
    xml.append("  <groupId>").append(metadataFile.groupId()).append("</groupId>\n");
    xml.append("  <artifactId>").append(metadataFile.artifactId()).append("</artifactId>\n");
    xml.append("  <versioning>\n");
    xml.append("    <latest>").append(latest).append("</latest>\n");
    xml.append("    <release>").append(latest).append("</release>\n");
    xml.append("    <versions>\n");
    for (StoredArtifact stored : versions) {
      xml.append("      <version>").append(stored.version()).append("</version>\n");
    }
    xml.append("    </versions>\n");
    xml.append("    <lastUpdated>")
        .append(MAVEN_TIMESTAMP.format(Instant.ofEpochMilli(lastUpdatedEpochMilli)))
        .append("</lastUpdated>\n");
    xml.append("  </versioning>\n");
    xml.append("</metadata>\n");
    return xml.toString();
  }

  private static String sha256Hex(byte[] bytes) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest(bytes));
    } catch (NoSuchAlgorithmException e) {
      // SHA-256 is mandatory in every conforming JRE; this is unreachable on a working JDK.
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }

  /**
   * The single authorization point every {@code /artifacts/*} and {@code /repository/*} route
   * passes through. Plaintext mode is open, matching every other Gimlé surface's plaintext posture.
   * Under mTLS: a {@code gimle:nodes} principal (a node agent's certificate identity, CN = its
   * nodeId) may only {@link Verb#READ}, and only a full coordinate its node currently holds an
   * assignment for -- never a push or delete, and never the catalog/version listings, which the
   * pull path has no use for -- while everything else goes through this process's own independent
   * {@link Authorizer} check against RBAC state it reads itself. Push/delete decisions are audited
   * both ways ({@code FafnirServer}'s dual-audit shape); reads are deliberately not durably audited
   * -- pulls are the high-volume path, and a pull discloses only what a deployment manifest already
   * references.
   */
  private boolean authorizeArtifacts(
      HttpExchange exchange, Verb verb, String target, String moduleId, String version)
      throws IOException {
    if (!(exchange instanceof HttpsExchange)) {
      return true;
    }
    Optional<Principal> resolved = resolvePrincipal(exchange);
    if (resolved.isEmpty()) {
      respond(exchange, 401, "authentication required");
      return false;
    }
    Principal principal = resolved.get();
    boolean allowed =
        principal.groups().contains(BuiltinRoles.GROUP_NODES)
            ? verb == Verb.READ
                && moduleId != null
                && version != null
                && nodeHasAssignmentFor(principal.name(), moduleId, version)
            : authorizer.authorize(
                principal, ResourceKind.ARTIFACT, verb, Optional.empty(), Optional.empty());
    if (verb != Verb.READ) {
      recordAudit(principal, verb, target, allowed);
    }
    if (!allowed) {
      respond(exchange, 403, "forbidden");
      return false;
    }
    return true;
  }

  /**
   * Whether {@code nodeId} currently holds any assignment -- deployment instance, job run,
   * daemonset, or statefulset -- for the requested coordinate: the artifact-registry analogue of
   * {@code FafnirServer}'s node-tenant scoping check, and the same shape of honest coarseness (an
   * O(all-assignments) walk per decision, since the store keys no assignment kind by node). The
   * walk only runs on a node's genuine cache misses -- a cache-hit install never reaches this
   * process at all -- so pull volume, not assignment volume, is what keeps it cheap in practice.
   */
  private boolean nodeHasAssignmentFor(String nodeId, String moduleId, String version) {
    for (InstanceAssignment assignment : storeClient.listAssignments()) {
      if (!assignment.nodeId().equals(nodeId)) {
        continue;
      }
      ModuleId assigned = assignment.moduleId();
      if (InstanceAssignment.UNSPECIFIED_MODULE.equals(assigned)) {
        assigned =
            storeClient
                .getDeployment(assignment.deploymentName())
                .map(DeploymentSpec::moduleId)
                .orElse(assigned);
      }
      if (coordinateMatches(assigned, moduleId, version)) {
        return true;
      }
    }
    for (JobRun run : storeClient.listJobRuns()) {
      if (run.nodeId().equals(nodeId) && coordinateMatches(run.moduleId(), moduleId, version)) {
        return true;
      }
    }
    for (DaemonSetAssignment assignment : storeClient.listDaemonSetAssignments()) {
      if (assignment.nodeId().equals(nodeId)
          && coordinateMatches(assignment.moduleId(), moduleId, version)) {
        return true;
      }
    }
    for (StatefulSetAssignment assignment : storeClient.listStatefulSetAssignments()) {
      if (assignment.nodeId().equals(nodeId)
          && coordinateMatches(assignment.moduleId(), moduleId, version)) {
        return true;
      }
    }
    return false;
  }

  private static boolean coordinateMatches(ModuleId assigned, String moduleId, String version) {
    return assigned != null
        && assigned.name().equals(moduleId)
        && assigned.version().toString().equals(version);
  }

  /** Dual audit for a push/delete decision: the tailable SLF4J line plus the durable event. */
  private void recordAudit(Principal principal, Verb verb, String target, boolean allowed) {
    auditLog.info(
        "principal={} target={} verb={} allow={}", principal.name(), target, verb, allowed);
    storeClient.propose(
        new StateMutation.AppendAuditEvent(
            new AuditEvent(
                UUID.randomUUID().toString(),
                principal.name(),
                principal.groups(),
                ResourceKind.ARTIFACT.name(),
                verb.name(),
                Optional.empty(),
                Optional.of(target),
                allowed,
                System.currentTimeMillis())));
  }

  /**
   * A forwarded principal (set only by {@code ApiServer}'s proxy) wins over the connection's own
   * peer certificate, since a proxied request's peer certificate identifies the control-plane
   * replica making the call, not the operator who originated it; falls back to the peer certificate
   * for a direct caller (a node agent's own pull, the CLI talking straight to this port). No
   * session-cookie fallback -- Andvari has no console login of its own.
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
      if (certificates.length == 0 || !(certificates[0] instanceof X509Certificate leaf)) {
        return Optional.empty();
      }
      return Optional.of(leaf);
    } catch (SSLPeerUnverifiedException | RuntimeException e) {
      return Optional.empty();
    }
  }

  private static Map<String, Object> versionJson(StoredArtifact stored) {
    Map<String, Object> json = new LinkedHashMap<>();
    json.put("moduleId", stored.moduleId());
    json.put("version", stored.version());
    json.put("sha256", stored.sha256());
    json.put("sizeBytes", stored.sizeBytes());
    json.put("pushedAtEpochMilli", stored.pushedAtEpochMilli());
    json.put("pushedBy", stored.pushedBy());
    return json;
  }

  private static void respond(HttpExchange exchange, int status, String body) throws IOException {
    respondBytes(
        exchange, status, "text/plain; charset=utf-8", body.getBytes(StandardCharsets.UTF_8));
  }

  private static void respondJson(HttpExchange exchange, int status, Object value)
      throws IOException {
    respondBytes(
        exchange,
        status,
        "application/json; charset=utf-8",
        Json.write(value).getBytes(StandardCharsets.UTF_8));
  }

  private static void respondBytes(
      HttpExchange exchange, int status, String contentType, byte[] bytes) throws IOException {
    exchange.getResponseHeaders().add("Content-Type", contentType);
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
