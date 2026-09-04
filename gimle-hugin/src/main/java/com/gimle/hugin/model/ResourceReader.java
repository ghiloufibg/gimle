package com.gimle.hugin.model;

import com.gimle.cli.CliException;
import com.gimle.cli.CliExitCode;
import com.gimle.cli.spi.ClusterReader;
import com.gimle.core.protocol.Json;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Reads one kind's collection route into a {@link ResourceSnapshot}, resolving each of that kind's
 * declared columns against every object the route answers with.
 *
 * <p>One reader for every kind rather than one per kind: they differ only in the route called and
 * the paths read, both of which the {@link ResourceKind} already carries. A per-kind reader would
 * mean a dozen near-identical classes drifting apart as each gained a fix the others didn't.
 *
 * <p>Field resolution is deliberately total -- an unresolvable path yields an empty cell, never an
 * error. The paths come from two sources allowed to be wrong about a given response: this module's
 * own built-in definitions, and print columns authored by whoever registered a custom kind.
 */
public final class ResourceReader {

  private final ClusterReader reader;
  private final ResourceKind kind;
  private final Optional<String> tenantId;

  public ResourceReader(
      final ClusterReader reader, final ResourceKind kind, final Optional<String> tenantId) {
    this.reader = reader;
    this.kind = kind;
    this.tenantId = tenantId;
  }

  public ResourceSnapshot read() {
    List<Map<String, Object>> objects;
    try {
      objects = list();
    } catch (CliException e) {
      if (e.exitCode() == CliExitCode.FORBIDDEN) {
        return ResourceSnapshot.forbidden(reader.serverAddress(), kind);
      }
      throw e;
    }
    List<ResourceRow> rows = new ArrayList<>();
    for (Map<String, Object> object : objects) {
      rows.add(row(object));
    }
    return new ResourceSnapshot(
        reader.serverAddress(), Optional.of(Instant.now()), kind, rows, true, Optional.empty());
  }

  /**
   * Most collection routes answer with a bare array; {@code /volumes} wraps its own in an object
   * alongside the nodes it could not reach. The wrapping key travels on the kind rather than being
   * sniffed from the response, so an unexpected shape reads as empty instead of being guessed at.
   */
  private List<Map<String, Object>> list() {
    String route = kind.routeFor(tenantId.orElse(""));
    if (kind.envelope().isEmpty()) {
      return objects(reader.getList(route));
    }
    Object wrapped = reader.getObject(route).get(kind.envelope().get());
    return wrapped instanceof List<?> list ? objects(list) : List.of();
  }

  /**
   * Two routes answer with an array of bare names rather than of objects. Each name becomes a
   * one-field object so it browses through the same path everything else does -- the alternative is
   * a second row type that exists only to carry a string.
   */
  private static List<Map<String, Object>> objects(final List<?> items) {
    List<Map<String, Object>> objects = new ArrayList<>();
    for (Object item : items) {
      if (item instanceof String name) {
        objects.add(Map.of("name", name));
      } else if (item instanceof Map<?, ?>) {
        objects.add(withoutDecryptedSecrets(Json.asObject(item)));
      }
    }
    return objects;
  }

  /**
   * A flat config listing carries every entry's value already decrypted, the encrypted ones
   * included -- the one place a read this view makes hands back a secret. The entry keeps its row
   * and loses its value: what a tenant holds is worth seeing, what is in it is not something a
   * dashboard left open on a desk should be painting.
   *
   * <p>Done to the object before any row is built, so the describe pane cannot show what the table
   * withheld.
   */
  private static Map<String, Object> withoutDecryptedSecrets(final Map<String, Object> object) {
    if (!Boolean.TRUE.equals(object.get("encrypted"))) {
      return object;
    }
    Map<String, Object> redacted = new LinkedHashMap<>(object);
    redacted.put("value", "(encrypted)");
    return redacted;
  }

  private ResourceRow row(final Map<String, Object> object) {
    List<String> cells = new ArrayList<>();
    for (ResourceColumn column : kind.columns()) {
      cells.add(JsonPath.textAt(object, column.path()));
    }
    return new ResourceRow(
        JsonPath.textAt(object, kind.namePath()),
        kind.tenantPath()
            .map(path -> JsonPath.textAt(object, path))
            .filter(tenant -> !tenant.isBlank()),
        cells,
        object);
  }
}
