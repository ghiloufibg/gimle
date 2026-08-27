package com.gimle.ragnarok.target;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The subset of the control plane's HTTP API that Fenrir and Surtr actually call: submitting and
 * tearing down tenants and deployments, reading back placement and lifecycle-event data, and the
 * handful of probes both engines' recovery gates poll. A {@link ClusterTarget} implementation backs
 * this however suits it -- a harness-owned cluster can wrap its own broader test-only API client; a
 * real cluster gets a small, independent HTTP implementation.
 */
public interface ControlPlaneClient {

  /**
   * Seeds or updates a tenant's quota. Takes the quota fields as primitives rather than a value
   * type on purpose -- it keeps this interface free of any type an implementation would need a
   * shared cross-module definition for; a caller with its own richer quota type unpacks it here.
   */
  int tryPutTenant(String tenantId, long maxMemoryBytes, long maxCpuMillicores, int maxInstances);

  int trySubmitDeployment(
      String deploymentName,
      String moduleName,
      String moduleVersion,
      Path jar,
      int replicas,
      Optional<String> tenantId);

  void deleteDeployment(String deploymentName);

  void deleteTenant(String tenantId);

  boolean isDeploymentActive(String deploymentName);

  int activeInstanceCount(String deploymentName);

  List<InstancePlacement> placements(String deploymentName);

  List<Map<String, Object>> instanceEvents(String deploymentName, int instanceIndex);

  void putSecret(String tenantId, String key, String value);

  /** True while this replica answers requests at all -- the control-plane-bounce recovery gate. */
  boolean isServing();

  /** Where one instance landed and what lifecycle state it last reported. */
  record InstancePlacement(int instanceIndex, String nodeId, String lifecycleState) {}
}
