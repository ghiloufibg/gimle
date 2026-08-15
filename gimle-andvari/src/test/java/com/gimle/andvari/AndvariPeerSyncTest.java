package com.gimle.andvari;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.andvari.testsupport.InProcessStore;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

/**
 * Exercises {@link AndvariPeerSync#sync()} directly against two real {@link AndvariServer}
 * replicas over real loopback sockets, the same "call the scheduled method directly rather than
 * wait on its own interval" posture {@code IntegrityScrubberTest}/{@code
 * ArtifactRetentionSweeperTest} already take toward their own schedulers.
 */
@ResourceLock(Resources.SYSTEM_PROPERTIES)
@ResourceLock("gimle-andvari-server-http")
class AndvariPeerSyncTest {

  @TempDir Path tempDir;

  private final HttpClient httpClient = HttpClient.newHttpClient();

  @Test
  @Timeout(10)
  void a_sync_tick_pulls_an_artifact_that_only_exists_on_a_peer() throws Exception {
    try (InProcessStore storeA = InProcessStore.start(tempDir.resolve("store-a"));
        InProcessStore storeB = InProcessStore.start(tempDir.resolve("store-b"));
        AndvariServer replicaA = new AndvariServer(storeA.client(), 0, tempDir.resolve("data-a"));
        AndvariServer replicaB = new AndvariServer(storeB.client(), 0, tempDir.resolve("data-b"))) {
      replicaA.start();
      replicaB.start();
      replicaA
          .artifactStore()
          .put("com.example.app", "1.0.0", bytes("peer-sync-bytes"), "ana");
      assertTrue(replicaB.artifactStore().meta("com.example.app", "1.0.0").isEmpty());

      // A long interval, not zero: the constructor's own initial (delay-0) scheduled run and this
      // test's direct sync() call below race harmlessly on which of the two actually performs the
      // pull -- the same posture IntegrityScrubberTest/ArtifactRetentionSweeperTest already take
      // toward their own schedulers -- so the assertion that matters is the resulting state, not
      // which call's return value reports the pull.
      try (AndvariPeerSync peerSync =
          new AndvariPeerSync(
              replicaB.artifactStore(),
              List.of(baseUri(replicaA)),
              httpClient,
              Duration.ofDays(1))) {
        peerSync.sync();
      }

      assertTrue(replicaB.artifactStore().meta("com.example.app", "1.0.0").isPresent());
      assertEquals(
          replicaA.artifactStore().meta("com.example.app", "1.0.0").orElseThrow().sha256(),
          replicaB.artifactStore().meta("com.example.app", "1.0.0").orElseThrow().sha256());
    }
  }

  @Test
  @Timeout(10)
  void a_push_to_one_replica_becomes_visible_through_another_after_a_sync_tick() throws Exception {
    try (InProcessStore storeA = InProcessStore.start(tempDir.resolve("store-a"));
        InProcessStore storeB = InProcessStore.start(tempDir.resolve("store-b"));
        AndvariServer replicaA = new AndvariServer(storeA.client(), 0, tempDir.resolve("data-a"));
        AndvariServer replicaB = new AndvariServer(storeB.client(), 0, tempDir.resolve("data-b"))) {
      replicaA.start();
      replicaB.start();

      push(baseUri(replicaA), "com.example.app", "2.0.0", "pushed-through-a");

      try (AndvariPeerSync peerSync =
          new AndvariPeerSync(
              replicaB.artifactStore(),
              List.of(baseUri(replicaA)),
              httpClient,
              Duration.ofDays(1))) {
        peerSync.sync();
      }

      byte[] downloaded = download(baseUri(replicaB), "com.example.app", "2.0.0");
      assertEquals("pushed-through-a", new String(downloaded, StandardCharsets.UTF_8));
    }
  }

  @Test
  @Timeout(10)
  void an_already_present_coordinate_is_never_re_pulled() throws Exception {
    try (InProcessStore storeA = InProcessStore.start(tempDir.resolve("store-a"));
        InProcessStore storeB = InProcessStore.start(tempDir.resolve("store-b"));
        AndvariServer replicaA = new AndvariServer(storeA.client(), 0, tempDir.resolve("data-a"));
        AndvariServer replicaB = new AndvariServer(storeB.client(), 0, tempDir.resolve("data-b"))) {
      replicaA.start();
      replicaB.start();
      replicaA.artifactStore().put("com.example.app", "1.0.0", bytes("same-bytes"), "ana");
      replicaB.artifactStore().put("com.example.app", "1.0.0", bytes("same-bytes"), "ana");

      try (AndvariPeerSync peerSync =
          new AndvariPeerSync(
              replicaB.artifactStore(),
              List.of(baseUri(replicaA)),
              httpClient,
              Duration.ofDays(1))) {
        int pulled = peerSync.sync();
        assertEquals(0, pulled);
      }
      assertFalse(replicaB.artifactStore().meta("com.example.app", "1.0.0").isEmpty());
    }
  }

  private void push(URI baseUri, String moduleId, String version, String content)
      throws Exception {
    HttpResponse<String> response =
        httpClient.send(
            HttpRequest.newBuilder(baseUri.resolve("/artifacts/" + moduleId + "/" + version))
                .PUT(
                    HttpRequest.BodyPublishers.ofByteArray(
                        content.getBytes(StandardCharsets.UTF_8)))
                .build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    assertEquals(200, response.statusCode());
  }

  private byte[] download(URI baseUri, String moduleId, String version) throws Exception {
    HttpResponse<byte[]> response =
        httpClient.send(
            HttpRequest.newBuilder(baseUri.resolve("/artifacts/" + moduleId + "/" + version))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofByteArray());
    assertEquals(200, response.statusCode());
    return response.body();
  }

  private static URI baseUri(AndvariServer server) {
    return URI.create("http://127.0.0.1:" + server.port());
  }

  private static ByteArrayInputStream bytes(String content) {
    return new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
  }
}
