package com.gimle.controlplane.admission;

import com.gimle.controlplane.configmap.ConfigMap;
import com.gimle.controlplane.configmap.ConfigMapCodec;
import com.gimle.core.config.ConfigEntry;
import com.gimle.mimir.manifest.DeploymentSpec;
import com.gimle.mimir.store.StoreReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Rejects a Deployment submission whose {@code secretMapRefs} don't resolve cleanly: every named
 * SecretMap must actually exist for the deployment's tenant, no two referenced SecretMaps may
 * declare the same key, and no referenced key may collide with a {@code configMapRefs} key or the
 * tenant's own flat config/secret keys. Mirrors {@link ConfigMapRefsPlugin} exactly -- every
 * SecretMap key is delivered to an instance as a flat {@code ControlMessage.ConfigDelivered} entry
 * the same way a ConfigMap key is (see {@code AgentMain#deliverConfig}), so two sources naming the
 * same key have no well-defined winner.
 *
 * <p>Reads Fafnir's own data directly off this admission request's {@link StoreReader} rather than
 * calling Fafnir over HTTP: a SecretMap member's {@code key@meta} row is unencrypted plain JSON
 * (see {@code com.gimle.fafnir.SecretStore}'s own javadoc) sharing the identical {@code
 * ConfigEntry} store this plugin already has a reader for, so its existence and its bare key name
 * are both readable without ever touching secret *values*. {@code
 * com.gimle.fafnir.secretmap.SecretMapCodec} owns this {@code "secretmap:" + name + ":" + key}
 * convention canonically; gimle-controlplane has no compile dependency on gimle-fafnir by design,
 * so the small pattern-matcher below is a deliberately duplicated mirror of it, the same precedent
 * {@link #isFafnirManagedSecretKey} already establishes for Fafnir's {@code key@meta}/{@code key@N}
 * convention.
 */
public final class SecretMapRefsPlugin implements AdmissionPlugin<DeploymentSpec> {

  private static final String SECRETMAP_KEY_PREFIX = "secretmap:";
  private static final String META_SUFFIX = "@meta";

  @Override
  public AdmissionDecision<DeploymentSpec> review(AdmissionRequest<DeploymentSpec> request) {
    DeploymentSpec spec = request.spec();
    if (spec.secretMapRefs().isEmpty()) {
      return AdmissionDecision.allow(spec);
    }
    if (spec.tenantId().isEmpty()) {
      return AdmissionDecision.reject(
          "deployment " + spec.name() + " declares secretMapRefs but has no tenantId");
    }
    String tenantId = spec.tenantId().get();
    StoreReader store = request.store();

    // key -> a human-readable name of the source that first declared it, so a later collision's
    // message can name both sources.
    Map<String, String> declaredBy = new HashMap<>();
    for (String refName : spec.secretMapRefs()) {
      List<String> memberKeys = secretMapMemberKeys(store, tenantId, refName);
      if (memberKeys.isEmpty()) {
        return AdmissionDecision.reject(
            "deployment "
                + spec.name()
                + " references unknown SecretMap '"
                + refName
                + "' for tenant "
                + tenantId);
      }
      for (String key : memberKeys) {
        String existingSource = declaredBy.putIfAbsent(key, "SecretMap '" + refName + "'");
        if (existingSource != null) {
          return AdmissionDecision.reject(
              "deployment "
                  + spec.name()
                  + ": key '"
                  + key
                  + "' is declared by both "
                  + existingSource
                  + " and SecretMap '"
                  + refName
                  + "'");
        }
      }
    }

    // Collide against configMapRefs' own declared keys -- an unknown configMapRefs entry is
    // ConfigMapRefsPlugin's own job to reject, not rechecked here.
    for (String refName : spec.configMapRefs()) {
      Optional<ConfigMap> configMap = ConfigMapCodec.find(store, tenantId, refName);
      if (configMap.isEmpty()) {
        continue;
      }
      for (String key : configMap.get().data().keySet()) {
        String source = declaredBy.get(key);
        if (source != null) {
          return AdmissionDecision.reject(
              "deployment "
                  + spec.name()
                  + ": key '"
                  + key
                  + "' is declared by "
                  + source
                  + " and also by ConfigMap '"
                  + refName
                  + "'");
        }
      }
    }

    // Collide against the tenant's own flat config and flat secret keys.
    for (ConfigEntry entry : store.listConfigEntriesFor(tenantId)) {
      String rawKey = entry.key();
      if (ConfigMapCodec.isConfigMapKey(rawKey) || rawKey.startsWith(SECRETMAP_KEY_PREFIX)) {
        continue; // handled above; never itself a flat key
      }
      String flatKey;
      String sourceKind;
      if (isFafnirManagedSecretKey(rawKey)) {
        if (!rawKey.endsWith(META_SUFFIX)) {
          continue; // only @meta carries the bare key name once; @N would just duplicate the check
        }
        flatKey = rawKey.substring(0, rawKey.length() - META_SUFFIX.length());
        sourceKind = "flat secret";
      } else {
        flatKey = rawKey;
        sourceKind = "flat config";
      }
      String source = declaredBy.get(flatKey);
      if (source != null) {
        return AdmissionDecision.reject(
            "deployment "
                + spec.name()
                + ": key '"
                + flatKey
                + "' is declared by "
                + source
                + " and also exists as "
                + sourceKind
                + " for tenant "
                + tenantId);
      }
    }
    return AdmissionDecision.allow(spec);
  }

  /**
   * Every member key currently under SecretMap {@code name} for {@code tenantId} -- resolved by
   * filtering the tenant's own {@code ConfigEntry} rows for {@code secretmap:{name}:{key}@meta},
   * the identical filter-then-decode shape {@code ConfigMapCodec.findAll} already uses for
   * ConfigMap's own convention. Empty means the name doesn't exist (or every one of its keys has
   * been hard-deleted).
   */
  private static List<String> secretMapMemberKeys(StoreReader store, String tenantId, String name) {
    String ownPrefix = SECRETMAP_KEY_PREFIX + name + ":";
    List<String> keys = new ArrayList<>();
    for (ConfigEntry entry : store.listConfigEntriesFor(tenantId)) {
      String rawKey = entry.key();
      if (rawKey.startsWith(ownPrefix) && rawKey.endsWith(META_SUFFIX)) {
        keys.add(rawKey.substring(ownPrefix.length(), rawKey.length() - META_SUFFIX.length()));
      }
    }
    return keys;
  }

  /**
   * Mirrors {@code ApiServer.isFafnirManagedSecretKey} -- a Fafnir-owned {@code key@meta}/{@code
   * key@N} row is neither flat config nor a ConfigMap/SecretMap, so it must never collide-check as
   * either of those, only ever as a flat secret.
   */
  private static boolean isFafnirManagedSecretKey(String key) {
    int at = key.lastIndexOf('@');
    if (at < 0 || at == key.length() - 1) {
      return false;
    }
    String suffix = key.substring(at + 1);
    return suffix.equals("meta") || suffix.chars().allMatch(Character::isDigit);
  }
}
