package com.gimle.ivaldi.blueprint;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The handful of top-level fields {@link BlueprintStore} reads out of an otherwise-opaque Blueprint
 * document to render the console's list screen -- everything else about a Blueprint (its nodes,
 * edges, canvas positions) is the console's own concern and passes through the store unread.
 */
public record BlueprintSummary(String id, String name, String version, String updatedAt) {

  public Map<String, Object> toJsonMap() {
    Map<String, Object> json = new LinkedHashMap<>();
    json.put("id", id);
    json.put("name", name);
    json.put("version", version);
    json.put("updatedAt", updatedAt);
    return json;
  }
}
