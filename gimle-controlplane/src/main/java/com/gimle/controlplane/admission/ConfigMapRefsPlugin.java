package com.gimle.controlplane.admission;

import com.gimle.controlplane.configmap.ConfigMap;
import com.gimle.controlplane.configmap.ConfigMapCodec;
import com.gimle.core.config.ConfigEntry;
import com.gimle.mimir.manifest.DeploymentSpec;
import com.gimle.mimir.store.StoreReader;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Rejects a Deployment submission whose {@code configMapRefs} don't resolve cleanly: every named
 * ConfigMap must actually exist for the deployment's tenant, no two referenced ConfigMaps may
 * declare the same key, and no referenced key may collide with the tenant's own flat config keys.
 * Each key is delivered to an instance as a flat {@code ControlMessage.ConfigDelivered} entry (see
 * {@code AgentMain#deliverConfig}), so two sources naming the same key have no well-defined winner
 * -- rejecting the collision loudly at submission time, the same posture this design already takes
 * for a stale {@code expectedVersion} write, beats silently picking one source over another at
 * instance-start time. Empty {@code configMapRefs} is allowed immediately with no extra store
 * reads, keeping every unscoped deployment submitted before this plugin existed unaffected.
 */
public final class ConfigMapRefsPlugin implements AdmissionPlugin<DeploymentSpec> {

  @Override
  public AdmissionDecision<DeploymentSpec> review(AdmissionRequest<DeploymentSpec> request) {
    DeploymentSpec spec = request.spec();
    if (spec.configMapRefs().isEmpty()) {
      return AdmissionDecision.allow(spec);
    }
    if (spec.tenantId().isEmpty()) {
      return AdmissionDecision.reject(
          "deployment " + spec.name() + " declares configMapRefs but has no tenantId");
    }
    String tenantId = spec.tenantId().get();
    StoreReader store = request.store();

    // key -> the ConfigMap name that first declared it, so a later collision's message can name
    // both sources.
    Map<String, String> declaredBy = new HashMap<>();
    for (String refName : spec.configMapRefs()) {
      Optional<ConfigMap> configMap = ConfigMapCodec.find(store, tenantId, refName);
      if (configMap.isEmpty()) {
        return AdmissionDecision.reject(
            "deployment "
                + spec.name()
                + " references unknown ConfigMap '"
                + refName
                + "' for tenant "
                + tenantId);
      }
      for (String key : configMap.get().data().keySet()) {
        String existingSource = declaredBy.putIfAbsent(key, refName);
        if (existingSource != null) {
          return AdmissionDecision.reject(
              "deployment "
                  + spec.name()
                  + ": key '"
                  + key
                  + "' is declared by both ConfigMap '"
                  + existingSource
                  + "' and '"
                  + refName
                  + "'");
        }
      }
    }

    for (ConfigEntry entry : store.listConfigEntriesFor(tenantId)) {
      if (ConfigMapCodec.isConfigMapKey(entry.key()) || isFafnirManagedSecretKey(entry.key())) {
        continue;
      }
      String source = declaredBy.get(entry.key());
      if (source != null) {
        return AdmissionDecision.reject(
            "deployment "
                + spec.name()
                + ": key '"
                + entry.key()
                + "' is declared by ConfigMap '"
                + source
                + "' and also exists as flat config for tenant "
                + tenantId);
      }
    }
    return AdmissionDecision.allow(spec);
  }

  /**
   * Mirrors {@code ApiServer.isFafnirManagedSecretKey} -- a Fafnir-owned {@code key@meta}/{@code
   * key@N} row is neither flat config nor a ConfigMap, so it must never collide-check as either.
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
