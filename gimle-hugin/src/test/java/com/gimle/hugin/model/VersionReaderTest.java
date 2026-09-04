package com.gimle.hugin.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Reading the four revision ledgers, each of which answers in its own shape. */
class VersionReaderTest {

  @Test
  void a_config_keys_ledger_reads_its_values_because_an_encrypted_write_never_enters_it() {
    FakeClusterReader reader =
        new FakeClusterReader()
            .withObject(
                "/config/acme/log.level/versions",
                Map.of(
                    "versions",
                    List.of(
                        Map.of("version", 1, "value", "INFO", "deleted", false),
                        Map.of("version", 2, "value", "DEBUG", "deleted", false))));

    List<VersionRow> rows = read(reader, "config", "log.level").rows();

    assertEquals(List.of(2, 1), rows.stream().map(VersionRow::version).toList());
    assertEquals("DEBUG", rows.getFirst().detail());
  }

  @Test
  void a_secrets_ledger_reads_who_wrote_each_revision_and_when_but_never_a_value() {
    FakeClusterReader reader =
        new FakeClusterReader()
            .withObject(
                "/secrets/acme/db.password/versions",
                Map.of(
                    "versions",
                    List.of(
                        Map.of(
                            "version",
                            4,
                            "author",
                            "ops@acme",
                            "writtenAtEpochMilli",
                            1_780_000_000_000L,
                            "type",
                            "OPAQUE"))));

    VersionRow row = read(reader, "secrets", "db.password").rows().getFirst();

    assertEquals(Optional.of("ops@acme"), row.author());
    assertEquals(Optional.of(Instant.ofEpochMilli(1_780_000_000_000L)), row.at());
    assertEquals("OPAQUE", row.detail());
  }

  @Test
  void a_configmaps_ledger_says_how_many_keys_a_revision_held_rather_than_listing_them() {
    FakeClusterReader reader =
        new FakeClusterReader()
            .withObject(
                "/configmaps/acme/limits/versions",
                Map.of(
                    "versions", List.of(Map.of("version", 2, "data", Map.of("a", "1", "b", "2")))));

    assertEquals("2 keys", read(reader, "configmaps", "limits").rows().getFirst().detail());
  }

  @Test
  void a_secretmaps_ledger_is_read_through_its_own_wrapper_and_version_field() {
    // It records a group version over member keys rather than one value's own revisions.
    FakeClusterReader reader =
        new FakeClusterReader()
            .withObject(
                "/secretmaps/acme/db/versions",
                Map.of(
                    "groupVersions",
                    List.of(Map.of("groupVersion", 3, "keys", List.of(Map.of("key", "user"))))));

    VersionRow row = read(reader, "secretmaps", "db").rows().getFirst();

    assertEquals(3, row.version());
    assertEquals("1 key", row.detail());
  }

  @Test
  void a_revision_recorded_as_deleted_says_so() {
    FakeClusterReader reader =
        new FakeClusterReader()
            .withObject(
                "/config/acme/gone/versions",
                Map.of("versions", List.of(Map.of("version", 2, "value", "", "deleted", true))));

    assertTrue(read(reader, "config", "gone").rows().getFirst().deleted());
  }

  @Test
  void the_revision_in_effect_is_the_newest_one_however_the_route_ordered_them() {
    FakeClusterReader reader =
        new FakeClusterReader()
            .withObject(
                "/config/acme/log.level/versions",
                Map.of(
                    "versions",
                    List.of(
                        Map.of("version", 7, "value", "WARN"),
                        Map.of("version", 9, "value", "ERROR"),
                        Map.of("version", 8, "value", "INFO"))));

    assertEquals(9, read(reader, "config", "log.level").current().orElseThrow().version());
  }

  @Test
  void a_kind_that_keeps_no_ledger_says_that_rather_than_reading_an_empty_one() {
    // "No history kept" and "no history yet" are different answers about different things.
    VersionSnapshot snapshot =
        new VersionReader(new FakeClusterReader(), kind("tenants"), "acme", "acme").read();

    assertFalse(snapshot.available());
    assertFalse(VersionReader.supports(kind("tenants")));
    assertTrue(VersionReader.supports(kind("secrets")));
  }

  @Test
  void a_tenant_or_name_carrying_a_reserved_character_is_escaped_into_the_path() {
    String path = new VersionReader(new FakeClusterReader(), kind("config"), "a b", "x/y").path();

    assertEquals("/config/a+b/x%2Fy/versions", path);
  }

  @Test
  void the_filter_narrows_by_author_and_by_what_the_revision_was() {
    FakeClusterReader reader =
        new FakeClusterReader()
            .withObject(
                "/config/acme/log.level/versions",
                Map.of(
                    "versions",
                    List.of(
                        Map.of("version", 1, "value", "INFO"),
                        Map.of("version", 2, "value", "DEBUG"))));

    VersionSnapshot snapshot = read(reader, "config", "log.level");

    assertEquals(1, snapshot.matching("debug").size());
    assertEquals(2, snapshot.matching("").size());
  }

  private static VersionSnapshot read(
      final FakeClusterReader reader, final String kindKey, final String name) {
    return new VersionReader(reader, kind(kindKey), "acme", name).read();
  }

  private static ResourceKind kind(final String key) {
    return ResourceKind.builtIns().stream()
        .filter(candidate -> candidate.key().equals(key))
        .findFirst()
        .orElseThrow();
  }
}
