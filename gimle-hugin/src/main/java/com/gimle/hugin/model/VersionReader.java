package com.gimle.hugin.model;

import com.gimle.cli.spi.ClusterReader;
import com.gimle.core.protocol.Json;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * Reads one config key's, ConfigMap's, secret's or SecretMap's revision ledger.
 *
 * <p>Four routes with four answer shapes, so what differs per kind is written down once here rather
 * than branched through the reading: which key wraps the array, which field carries the revision
 * number, and what the one line about a revision says. Everything else -- the request, the
 * ordering, the failure handling -- is shared.
 *
 * <p>A revision's own contents are shown only where they cannot be a secret. The plaintext config
 * ledger records values and is safe to show, because an encrypted write never reaches it; the
 * secret and SecretMap ledgers record no value at all, only who wrote which revision and when.
 * Nothing here asks for a value.
 */
public final class VersionReader {

  /** What differs between the four ledgers, and nothing that doesn't. */
  private record Ledger(
      String envelope, String versionField, Function<Map<String, Object>, String> detail) {}

  private static final Map<String, Ledger> LEDGERS =
      Map.of(
          "config", new Ledger("versions", "version", entry -> text(entry.get("value"))),
          "configmaps", new Ledger("versions", "version", entry -> keyCount(entry.get("data"))),
          "secrets", new Ledger("versions", "version", entry -> text(entry.get("type"))),
          "secretmaps",
              new Ledger("groupVersions", "groupVersion", entry -> keyCount(entry.get("keys"))));

  private final ClusterReader reader;
  private final ResourceKind kind;
  private final String tenantId;
  private final String name;

  public VersionReader(
      final ClusterReader reader,
      final ResourceKind kind,
      final String tenantId,
      final String name) {
    this.reader = reader;
    this.kind = kind;
    this.tenantId = tenantId;
    this.name = name;
  }

  /** Whether this kind keeps a ledger at all -- what decides if the pane can be opened on it. */
  public static boolean supports(final ResourceKind kind) {
    return LEDGERS.containsKey(kind.key());
  }

  public VersionSnapshot read() {
    Ledger ledger = LEDGERS.get(kind.key());
    if (ledger == null) {
      return VersionSnapshot.unavailable(
          reader.serverAddress(), subject(), "a " + kind.label() + " keeps no revision history");
    }
    Object wrapped = reader.getObject(path()).get(ledger.envelope());
    List<VersionRow> rows = new ArrayList<>();
    if (wrapped instanceof List<?> list) {
      for (Map<String, Object> entry : Json.asObjectList(list)) {
        rows.add(row(entry, ledger));
      }
    }
    // Newest first: the revision in effect is the one being compared against, so it belongs where
    // the eye lands rather than at the end of a ledger that only grows.
    rows.sort(Comparator.comparingInt(VersionRow::version).reversed());
    return new VersionSnapshot(
        reader.serverAddress(),
        Optional.of(Instant.now()),
        subject(),
        rows,
        true,
        Optional.empty());
  }

  String path() {
    return kind.routeFor(encode(tenantId)) + "/" + encode(name) + "/versions";
  }

  private String subject() {
    return tenantId + "/" + name;
  }

  private static VersionRow row(final Map<String, Object> entry, final Ledger ledger) {
    return new VersionRow(
        number(entry.get(ledger.versionField())),
        optionalText(entry.get("author")),
        writtenAt(entry.get("writtenAtEpochMilli")),
        ledger.detail().apply(entry),
        Boolean.TRUE.equals(entry.get("deleted")));
  }

  private static String keyCount(final Object value) {
    int keys =
        switch (value) {
          case Map<?, ?> map -> map.size();
          case List<?> list -> list.size();
          case null, default -> 0;
        };
    return keys + (keys == 1 ? " key" : " keys");
  }

  private static Optional<Instant> writtenAt(final Object value) {
    return value instanceof Number millis
        ? Optional.of(Instant.ofEpochMilli(millis.longValue()))
        : Optional.empty();
  }

  private static int number(final Object value) {
    return value instanceof Number n ? n.intValue() : 0;
  }

  private static String text(final Object value) {
    return value instanceof String s ? s : "";
  }

  private static Optional<String> optionalText(final Object value) {
    return value instanceof String s && !s.isBlank() ? Optional.of(s) : Optional.empty();
  }

  private static String encode(final String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }
}
