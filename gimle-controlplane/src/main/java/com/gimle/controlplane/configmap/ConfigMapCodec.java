package com.gimle.controlplane.configmap;

import com.gimle.core.config.ConfigEntry;
import com.gimle.core.protocol.Json;
import com.gimle.mimir.store.StoreReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The synthetic-key convention layered over the plain {@link ConfigEntry} store: a ConfigMap named
 * {@code name} for tenant {@code tenantId} is one row keyed {@code "configmap:" + name}, {@code
 * encrypted=false}, its value a small JSON envelope {@code {"version": N, "data": {...}}} --
 * mirroring Fafnir's own {@code key@meta}/{@code key@N} convention over the identical store (see
 * {@link ConfigEntry}'s own javadoc). Deliberately {@link StoreReader}-only, not {@code
 * StoreClient}: {@code ConfigMapRefsPlugin} (an admission plugin, which only ever gets a {@link
 * StoreReader}) needs to resolve ConfigMaps too, and this class is the one place both it and {@link
 * ConfigMapStore} share that logic instead of duplicating it.
 */
public final class ConfigMapCodec {

  private static final Logger log = LoggerFactory.getLogger(ConfigMapCodec.class);
  public static final String KEY_PREFIX = "configmap:";

  private ConfigMapCodec() {}

  static String keyFor(String name) {
    return KEY_PREFIX + name;
  }

  /**
   * {@code true} for a {@code ConfigEntry} key this class's own convention owns -- reused by {@code
   * ApiServer} to reject a plain {@code /config/*} write/delete against this reserved prefix and to
   * filter these rows out of a plain {@code /config/*} listing, the same way it already filters out
   * Fafnir's own {@code key@meta}/{@code key@N} keys.
   */
  public static boolean isConfigMapKey(String key) {
    return key.startsWith(KEY_PREFIX);
  }

  static String nameFromKey(String key) {
    return key.substring(KEY_PREFIX.length());
  }

  static ConfigEntry encode(ConfigMap configMap) {
    Map<String, Object> envelope = new LinkedHashMap<>();
    envelope.put("version", configMap.version());
    envelope.put("data", configMap.data());
    byte[] value = Json.write(envelope).getBytes(StandardCharsets.UTF_8);
    return new ConfigEntry(configMap.tenantId(), keyFor(configMap.name()), value, false);
  }

  static ConfigMap decode(ConfigEntry entry) {
    String name = nameFromKey(entry.key());
    Map<String, Object> envelope =
        Json.asObject(Json.parse(new String(entry.value(), StandardCharsets.UTF_8)));
    int version = ((Number) envelope.get("version")).intValue();
    Map<String, Object> rawData = Json.asObject(envelope.get("data"));
    Map<String, String> data = new LinkedHashMap<>();
    for (Map.Entry<String, Object> e : rawData.entrySet()) {
      data.put(e.getKey(), String.valueOf(e.getValue()));
    }
    return new ConfigMap(entry.tenantId(), name, version, data);
  }

  /**
   * Every ConfigMap for {@code tenantId} -- a malformed row is skipped and logged rather than
   * aborting the whole listing, the same tolerant posture {@code SecretStore#list} takes for a
   * corrupted {@code @meta} entry.
   */
  public static List<ConfigMap> findAll(StoreReader store, String tenantId) {
    return decodeAll(tenantId, store.listConfigEntriesFor(tenantId));
  }

  public static Optional<ConfigMap> find(StoreReader store, String tenantId, String name) {
    return findAll(store, tenantId).stream().filter(cm -> cm.name().equals(name)).findFirst();
  }

  /**
   * Shared by {@link #findAll} (plain round-robin reads) and {@link ConfigMapStore}'s own
   * linearizable write-path read -- both start from a raw {@code ConfigEntry} list and need the
   * identical filter-decode-skip-malformed logic.
   */
  static List<ConfigMap> decodeAll(String tenantId, List<ConfigEntry> entries) {
    List<ConfigMap> result = new ArrayList<>();
    for (ConfigEntry entry : entries) {
      if (!isConfigMapKey(entry.key())) {
        continue;
      }
      try {
        result.add(decode(entry));
      } catch (RuntimeException e) {
        log.warn(
            "skipping malformed ConfigMap entry for {}/{}: {}",
            tenantId,
            entry.key(),
            e.getMessage());
      }
    }
    return result;
  }
}
