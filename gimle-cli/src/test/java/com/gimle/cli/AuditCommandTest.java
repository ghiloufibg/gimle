package com.gimle.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.core.protocol.AuditEvent;
import com.gimle.core.protocol.Json;
import com.gimle.mimir.raft.StateMutation;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceAccessMode;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

/**
 * {@code gimle audit list}'s paging against a real {@link com.gimle.controlplane.api.ApiServer}.
 * The lock is a read lock on the system properties every server here consults for its transport
 * mode -- this class never writes one, it only must not observe another class mid-change.
 */
@ResourceLock(value = Resources.SYSTEM_PROPERTIES, mode = ResourceAccessMode.READ)
class AuditCommandTest {

  @TempDir(cleanup = CleanupMode.NEVER)
  Path tempDir;

  private InProcessCluster cluster;
  private ByteArrayOutputStream outBuffer;
  private ByteArrayOutputStream errBuffer;
  private PrintStream out;
  private PrintStream err;

  @BeforeEach
  void startCluster() {
    cluster = InProcessCluster.start(tempDir);
    outBuffer = new ByteArrayOutputStream();
    errBuffer = new ByteArrayOutputStream();
    out = new PrintStream(outBuffer, true, StandardCharsets.UTF_8);
    err = new PrintStream(errBuffer, true, StandardCharsets.UTF_8);
  }

  @AfterEach
  void stopCluster() {
    cluster.close();
  }

  private int run(String... args) {
    String[] withServer = new String[args.length + 2];
    System.arraycopy(args, 0, withServer, 0, args.length);
    withServer[args.length] = "--server";
    withServer[args.length + 1] = cluster.address();
    return GimleCli.run(withServer, out, err);
  }

  private String stdout() {
    return outBuffer.toString(StandardCharsets.UTF_8);
  }

  private String stderr() {
    return errBuffer.toString(StandardCharsets.UTF_8);
  }

  private void seedAuditEvents(int count) {
    for (int i = 0; i < count; i++) {
      cluster
          .storeClient()
          .propose(
              new StateMutation.AppendAuditEvent(
                  new AuditEvent(
                      "seeded-" + i,
                      "alice",
                      Set.of(),
                      "DEPLOYMENT",
                      "WRITE",
                      Optional.of("acme"),
                      Optional.of("orders"),
                      true,
                      1_000L + i)));
    }
  }

  @Test
  void list_without_a_limit_still_returns_the_whole_trail_and_prints_no_paging_note() {
    seedAuditEvents(4);

    assertEquals(0, run("audit", "list", "--principal", "alice"), stderr());

    assertEquals(4, stdout().lines().filter(line -> line.contains("seeded-")).count());
    assertFalse(stdout().contains("--cursor"), stdout());
  }

  @Test
  void a_limited_list_says_how_many_matched_and_hands_back_a_cursor_for_the_rest() {
    seedAuditEvents(4);

    assertEquals(0, run("audit", "list", "--principal", "alice", "--limit", "2"), stderr());

    assertTrue(stdout().contains("showing 2 of 4 matching event(s)"), stdout());
    assertTrue(stdout().contains("note: more events match; continue with --cursor "), stdout());
  }

  @Test
  void resuming_from_that_cursor_returns_the_events_the_first_page_left_behind() {
    seedAuditEvents(4);
    run("audit", "list", "--principal", "alice", "--limit", "2");
    String cursor = cursorFrom(stdout());
    outBuffer.reset();

    assertEquals(
        0,
        run("audit", "list", "--principal", "alice", "--limit", "2", "--cursor", cursor),
        stderr());

    assertTrue(stdout().contains("seeded-1"), stdout());
    assertTrue(stdout().contains("seeded-0"), stdout());
    assertFalse(stdout().contains("seeded-3"), stdout());
  }

  @Test
  void all_follows_every_page_so_a_limited_query_still_dumps_the_whole_filtered_trail() {
    seedAuditEvents(5);

    assertEquals(
        0, run("audit", "list", "--principal", "alice", "--limit", "2", "--all"), stderr());

    assertEquals(5, stdout().lines().filter(line -> line.contains("seeded-")).count());
    assertFalse(stdout().contains("--cursor"), stdout());
  }

  /** Paging notes would corrupt {@code -o json}'s single parseable document. */
  @Test
  void json_output_stays_a_single_parseable_array_even_when_more_pages_remain() {
    seedAuditEvents(4);

    assertEquals(
        0, run("-o", "json", "audit", "list", "--principal", "alice", "--limit", "2"), stderr());

    List<Map<String, Object>> events = Json.asObjectList(Json.parse(stdout()));
    assertEquals(2, events.size());
  }

  private static String cursorFrom(String output) {
    return output
        .lines()
        .filter(line -> line.startsWith("note: more events match"))
        .map(line -> line.substring(line.lastIndexOf(' ') + 1))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no cursor note in output:\n" + output));
  }
}
