package com.gimle.hugin.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * One resource in the browser: the cells its kind's columns resolved to, plus the whole response
 * object it came from.
 *
 * <p>The raw object is carried because the describe pane shows exactly what the control plane
 * answered rather than a re-read of it -- an operator comparing a row against its own detail must
 * never be shown two different reads and left to wonder which is current.
 *
 * <p>Copied through a {@link LinkedHashMap} rather than {@code Map.copyOf}: a resource's own JSON
 * legitimately carries null values (a custom resource with no status yet reports {@code status:
 * null}), and the immutable copy rejects those outright.
 */
public record ResourceRow(
    String name, Optional<String> tenantId, List<String> cells, Map<String, Object> raw) {

  public ResourceRow {
    if (name == null) {
      throw new IllegalArgumentException("name must not be null");
    }
    if (tenantId == null) {
      throw new IllegalArgumentException("tenantId must not be null; use Optional.empty()");
    }
    cells = List.copyOf(cells);
    raw = Collections.unmodifiableMap(new LinkedHashMap<>(raw));
  }

  /**
   * What a filter is matched against: the name, the tenant and every cell on the row. Typing what
   * is visible is what an operator expects to work, whichever kind is open.
   */
  public String searchText() {
    return (name + " " + tenantId.orElse("") + " " + String.join(" ", cells))
        .toLowerCase(Locale.ROOT);
  }

  /** How the row names itself when there is nothing to name it with. */
  public String displayName() {
    return name.isBlank() ? "—" : name;
  }
}
