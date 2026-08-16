package com.gimle.hilmir.release;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A workload's identity within the release ledger: its manifest {@code kind} plus its {@code name}.
 */
record ResourceRef(String kind, String name) {

  Map<String, Object> toJson() {
    Map<String, Object> json = new LinkedHashMap<>();
    json.put("kind", kind);
    json.put("name", name);
    return json;
  }
}
