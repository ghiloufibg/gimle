package com.gimle.hilmir.release;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A tenant-scoped key a release applied to the config store or the vault -- the second thing an
 * upgrade prunes, beside its workloads.
 *
 * <p>Without this, a key a release stopped declaring simply stayed where the previous revision put
 * it. Renaming a secret left the old one live in the vault, and because a config lookup falls back
 * to the vault for the same key, that orphan then silently answered reads meant for a config entry
 * the operator could see and had every reason to trust.
 */
public record KeyRef(String tenant, String key) {

  public Map<String, Object> toJson() {
    Map<String, Object> json = new LinkedHashMap<>();
    json.put("tenant", tenant);
    json.put("key", key);
    return json;
  }
}
