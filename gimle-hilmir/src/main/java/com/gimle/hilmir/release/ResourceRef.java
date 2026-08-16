package com.gimle.hilmir.release;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A workload's identity within the release ledger: its manifest {@code kind} plus its {@code name}.
 *
 * <p>Public so {@code com.gimle.hilmir.sync} can report the resources an upgrade pruned, the same
 * way {@link UpgradeCommand}'s own JSON output does.
 */
public record ResourceRef(String kind, String name) {

  public Map<String, Object> toJson() {
    Map<String, Object> json = new LinkedHashMap<>();
    json.put("kind", kind);
    json.put("name", name);
    return json;
  }
}
