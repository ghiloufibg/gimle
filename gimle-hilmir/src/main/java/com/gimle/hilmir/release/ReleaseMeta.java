package com.gimle.hilmir.release;

import com.gimle.core.protocol.Json;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The {@code hilmir.release.<name>.meta} pointer row: a small summary of a release's current state
 * -- which bundle produced it, its most recently applied revision number, and which tenants it
 * created -- not its full history (see {@link ReleaseRevision} for that).
 */
record ReleaseMeta(
    String bundleName, String bundleVersion, int currentRevision, List<String> tenants) {

  Map<String, Object> toJson() {
    Map<String, Object> json = new LinkedHashMap<>();
    json.put("bundleName", bundleName);
    json.put("bundleVersion", bundleVersion);
    json.put("currentRevision", currentRevision);
    json.put("tenants", List.copyOf(tenants));
    return json;
  }

  static ReleaseMeta fromJson(Map<String, Object> json) {
    List<String> tenants = new ArrayList<>();
    for (Object tenant : Json.asArray(json.get("tenants"))) {
      tenants.add((String) tenant);
    }
    return new ReleaseMeta(
        (String) json.get("bundleName"),
        (String) json.get("bundleVersion"),
        ((Number) json.get("currentRevision")).intValue(),
        tenants);
  }
}
