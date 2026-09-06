package com.gimle.andvari;

import com.gimle.andvari.ArtifactStore.PutOutcome;
import com.gimle.andvari.ArtifactStore.PutResult;
import com.gimle.core.module.ArtifactKind;
import com.gimle.core.protocol.Json;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Periodically replicates every other configured peer's catalog into this replica's own {@link
 * ArtifactStore} -- the mechanism that turns a list of independently-pushable Andvari replicas (see
 * {@code AndvariMain}'s own {@code --peer-endpoints}) into an actual highly-available registry
 * instead of just several unrelated single points of failure. No consensus protocol is needed for
 * this: {@link ArtifactStore#put} is already idempotent on identical bytes and hard-conflicts on
 * differing ones for the same {@code (moduleId, version)} coordinate, so any replica can accept a
 * push independently and every other replica can safely pull it whenever it next notices the
 * coordinate is missing locally.
 *
 * <p>Each tick walks every peer's catalog ({@code GET /artifacts}, then {@code GET
 * /artifacts/{moduleId}} for each module's versions), skips every coordinate already present
 * locally (an {@link ArtifactStore#meta} hit -- no network call needed to confirm a coordinate this
 * replica already committed), and pulls the rest through the same streamed download this store's
 * own commit path already trusts: the peer's jar bytes are handed straight to {@link
 * ArtifactStore#put}, which digests them itself while streaming to disk and commits atomically --
 * the identical mechanism a client's own {@code PUT} already goes through, so there is no second
 * digest-verification implementation to keep in sync with the first. The peer's own catalog entry
 * additionally carries the sha256 it expects; a mismatch against what actually landed here is
 * logged loudly and the newly-committed coordinate is quarantined via {@link
 * ArtifactStore#quarantine}, the same corruption-response path {@code AndvariServer} already uses
 * for a download-time digest mismatch.
 *
 * <p>Runs on its own virtual thread, the same lightweight-background-work idiom {@link
 * IntegrityScrubber} and {@link ArtifactRetentionSweeper} already establish in this module.
 */
final class AndvariPeerSync implements AutoCloseable {

  private static final Logger log = LoggerFactory.getLogger(AndvariPeerSync.class);
  private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
  private static final Duration TRANSFER_TIMEOUT = Duration.ofMinutes(2);

  private final ArtifactStore artifactStore;
  private final List<URI> peers;
  private final HttpClient httpClient;
  private final ScheduledExecutorService scheduler;

  AndvariPeerSync(
      ArtifactStore artifactStore, List<URI> peers, HttpClient httpClient, Duration syncInterval) {
    this.artifactStore = artifactStore;
    this.peers = List.copyOf(peers);
    this.httpClient = httpClient;
    this.scheduler =
        Executors.newSingleThreadScheduledExecutor(
            r -> Thread.ofVirtual().name("gimle-andvari-peer-sync").unstarted(r));
    scheduler.scheduleAtFixedRate(
        this::syncQuietly, 0, syncInterval.toMillis(), TimeUnit.MILLISECONDS);
  }

  /**
   * The scheduler's own entry point, and the barrier that keeps this replica syncing. {@link
   * java.util.concurrent.ScheduledExecutorService#scheduleAtFixedRate} cancels a repeating task
   * permanently the moment one execution throws, so anything escaping {@link #sync()} would end
   * peer replication for the rest of this process's life over a failure that is almost always
   * transient -- a peer restarting, a name that does not resolve for a few seconds. {@link Error}
   * is deliberately not caught: an {@code OutOfMemoryError} is not something the next tick can
   * recover from by retrying.
   */
  private void syncQuietly() {
    try {
      sync();
    } catch (RuntimeException e) {
      log.warn("peer sync failed: {}", Failures.describe(e), e);
    }
  }

  /**
   * Package-visible for direct testing without waiting on the scheduler's own interval. Returns the
   * number of coordinates newly pulled from any peer this pass.
   */
  int sync() {
    int pulled = 0;
    for (URI peer : peers) {
      try {
        pulled += syncFrom(peer);
      } catch (IOException | InterruptedException | RuntimeException e) {
        if (e instanceof InterruptedException) {
          // Restore the flag and stop this pass entirely rather than walking the remaining peers:
          // every further send on an interrupted thread fails instantly, which would turn one
          // shutdown signal into a burst of identical, meaningless failure lines.
          Thread.currentThread().interrupt();
          log.warn("peer sync from {} interrupted; abandoning this pass", peer);
          return pulled;
        }
        // Failures.describe, not getMessage(): the JDK HTTP client reports an unresolvable peer
        // host as a ConnectException carrying no message at all, which a bare getMessage() renders
        // as the literal text "null" -- the exact opposite of what this line exists to tell an
        // operator.
        log.warn("peer sync from {} failed: {}", peer, Failures.describe(e), e);
      }
    }
    if (pulled > 0) {
      log.info("peer sync pulled {} artifact(s) from {} configured peer(s)", pulled, peers.size());
    } else {
      log.debug("peer sync found nothing new across {} configured peer(s)", peers.size());
    }
    return pulled;
  }

  private int syncFrom(URI peer) throws IOException, InterruptedException {
    int pulled = 0;
    for (Object moduleIdValue : fetchCatalog(peer)) {
      String moduleId = String.valueOf(moduleIdValue);
      for (Map<String, Object> version : fetchVersions(peer, moduleId)) {
        String versionId = String.valueOf(version.get("version"));
        if (artifactStore.meta(moduleId, versionId).isPresent()) {
          continue; // already have this coordinate; no network call needed to confirm it further
        }
        Optional<String> tenantId = Optional.ofNullable((String) version.get("tenantId"));
        ArtifactKind kind = ArtifactKind.parse((String) version.get("kind"));
        if (pullOne(
            peer, moduleId, versionId, String.valueOf(version.get("sha256")), tenantId, kind)) {
          pulled++;
        }
      }
    }
    return pulled;
  }

  private List<Object> fetchCatalog(URI peer) throws IOException, InterruptedException {
    HttpResponse<String> response =
        httpClient.send(
            HttpRequest.newBuilder(peer.resolve("/artifacts"))
                .timeout(REQUEST_TIMEOUT)
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() != 200) {
      throw new IOException(
          "peer " + peer + " answered " + response.statusCode() + " for /artifacts");
    }
    return Json.asArray(Json.parse(response.body()));
  }

  private List<Map<String, Object>> fetchVersions(URI peer, String moduleId)
      throws IOException, InterruptedException {
    URI uri = peer.resolve("/artifacts/" + moduleId);
    HttpResponse<String> response =
        httpClient.send(
            HttpRequest.newBuilder(uri).timeout(REQUEST_TIMEOUT).GET().build(),
            HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() == 404) {
      // A module the catalog just listed can still race to empty between the two calls (e.g. its
      // last version was deleted concurrently) -- nothing to pull, not a failure worth logging.
      return List.of();
    }
    if (response.statusCode() != 200) {
      throw new IOException("peer " + peer + " answered " + response.statusCode() + " for " + uri);
    }
    Map<String, Object> body = Json.asObject(Json.parse(response.body()));
    return Json.asObjectList(body.get("versions"));
  }

  /**
   * Streams one coordinate straight from the peer into {@link ArtifactStore#put}, which does its
   * own digesting-while-streaming and atomic commit -- the same mechanism a client's own push
   * already goes through, reused rather than duplicated here. Returns whether a new local copy was
   * actually created.
   */
  private boolean pullOne(
      URI peer,
      String moduleId,
      String version,
      String expectedSha256,
      Optional<String> tenantId,
      ArtifactKind kind)
      throws IOException, InterruptedException {
    URI uri = peer.resolve("/artifacts/" + moduleId + "/" + version);
    HttpResponse<InputStream> response =
        httpClient.send(
            HttpRequest.newBuilder(uri).timeout(TRANSFER_TIMEOUT).GET().build(),
            HttpResponse.BodyHandlers.ofInputStream());
    if (response.statusCode() != 200) {
      // Closed explicitly rather than left for the garbage collector: an unconsumed streaming
      // body holds its connection until it is collected, and this line runs on every tick against
      // a peer that keeps refusing downloads.
      response.body().close();
      throw new IOException("peer " + peer + " answered " + response.statusCode() + " for " + uri);
    }
    PutResult result;
    try (InputStream body = response.body()) {
      // Always a fresh coordinate from this replica's own point of view -- syncFrom only ever
      // calls pullOne for a coordinate its own meta() lookup just confirmed is missing locally --
      // so this can only land as CREATED, never the untenanted-to-tenanted backfill branch.
      result =
          artifactStore.put(moduleId, version, body, "andvari-peer-sync:" + peer, tenantId, kind);
    }
    if (result.outcome() == PutOutcome.CONFLICT) {
      // Both replicas independently hold different bytes under the identical coordinate -- the
      // one divergence this store's immutability guarantee cannot self-heal, since neither
      // version is "more correct" than the other from here. Left as an operator-visible loud
      // failure (the same posture ArtifactStore#put already takes toward a client's own
      // conflicting re-push) rather than silently preferring either side.
      log.error(
          "peer sync conflict for {}:{} -- peer {} holds bytes hashing to a different sha256 than"
              + " this replica already committed; leaving the local copy untouched",
          moduleId,
          version,
          peer);
      return false;
    }
    if (result.outcome() == PutOutcome.CREATED
        && !result.stored().sha256().equalsIgnoreCase(expectedSha256)) {
      log.error(
          "peer sync for {}:{} from {} committed bytes hashing to {} but the peer's own catalog"
              + " advertised {}; quarantining",
          moduleId,
          version,
          peer,
          result.stored().sha256(),
          expectedSha256);
      artifactStore.quarantine(moduleId, version);
      return false;
    }
    return result.outcome() == PutOutcome.CREATED;
  }

  @Override
  public void close() {
    scheduler.shutdownNow();
  }
}
