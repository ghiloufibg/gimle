package com.gimle.hugin.model;

import com.gimle.cli.spi.ClusterReader;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Builds a {@link ServiceSnapshot} out of {@code GET /services} and, for each Service it names, one
 * {@code GET /services/{name}/endpoints} -- the live resolution the control plane computes off the
 * current store snapshot rather than any cached endpoint set, which is what makes "this Service
 * currently backs nothing" a reading rather than a guess.
 *
 * <p>Every field is read defensively, the same posture {@link SnapshotReader} takes: a response
 * missing something degrades that one column, never the whole read. The endpoint call is made per
 * Service and failures are absorbed individually for the same reason -- one Service the caller
 * cannot read endpoints for must not blank the other twenty.
 */
public final class ServiceReader {

  private final ClusterReader reader;

  public ServiceReader(final ClusterReader reader) {
    this.reader = reader;
  }

  public ServiceSnapshot read() {
    List<ServiceRow> rows = new ArrayList<>();
    for (Map<String, Object> service : reader.getList("/services")) {
      String name = string(service.get("name"));
      if (name.isBlank()) {
        continue;
      }
      Optional<String> tenantId = optionalString(service.get("tenantId"));
      rows.add(
          new ServiceRow(
              name,
              tenantId,
              deploymentNames(service.get("deploymentNames")),
              (int) number(service.get("port")),
              optionalPort(service.get("targetPort")),
              Boolean.TRUE.equals(service.get("sessionAffinity")),
              optionalString(service.get("externalName")),
              stringOrDefault(service.get("protocol"), "TCP"),
              endpointCount(name, tenantId)));
    }
    rows.sort(
        Comparator.comparing((ServiceRow row) -> row.tenantId().orElse(""))
            .thenComparing(ServiceRow::name));
    return new ServiceSnapshot(
        reader.serverAddress(), Optional.of(Instant.now()), rows, Optional.empty());
  }

  /**
   * Empty rather than zero whenever the answer isn't a readable endpoint array. Zero is the finding
   * this view is for, so a failed or unrecognisable response must not be able to manufacture one.
   */
  private OptionalInt endpointCount(final String name, final Optional<String> tenantId) {
    try {
      Object endpoints = reader.getObject(endpointsPath(name, tenantId)).get("endpoints");
      return endpoints instanceof List<?> list ? OptionalInt.of(list.size()) : OptionalInt.empty();
    } catch (RuntimeException e) {
      return OptionalInt.empty();
    }
  }

  /**
   * The owning tenant travels as {@code ?tenant=<id>}, the same way every other tenant-scoped route
   * expects it -- without it, a name two tenants both use resolves against whichever the untenanted
   * namespace happens to hold.
   */
  private static String endpointsPath(final String name, final Optional<String> tenantId) {
    return "/services/"
        + encode(name)
        + "/endpoints"
        + tenantId.map(id -> "?tenant=" + encode(id)).orElse("");
  }

  private static List<String> deploymentNames(final Object value) {
    if (!(value instanceof List<?> list)) {
      return List.of();
    }
    List<String> names = new ArrayList<>();
    for (Object entry : list) {
      String name = string(entry);
      if (!name.isBlank()) {
        names.add(name);
      }
    }
    return names;
  }

  private static String encode(final String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  private static long number(final Object value) {
    return value instanceof Number n ? n.longValue() : 0L;
  }

  private static OptionalInt optionalPort(final Object value) {
    return value instanceof Number n ? OptionalInt.of(n.intValue()) : OptionalInt.empty();
  }

  private static String string(final Object value) {
    return value instanceof String s ? s : "";
  }

  private static String stringOrDefault(final Object value, final String fallback) {
    String text = string(value);
    return text.isBlank() ? fallback : text;
  }

  private static Optional<String> optionalString(final Object value) {
    String text = string(value);
    return text.isBlank() ? Optional.empty() : Optional.of(text);
  }
}
