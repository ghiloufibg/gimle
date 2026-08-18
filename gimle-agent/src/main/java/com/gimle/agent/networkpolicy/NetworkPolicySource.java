package com.gimle.agent.networkpolicy;

import com.gimle.core.tenant.NetworkPolicyRule;
import java.io.IOException;
import java.util.List;

/**
 * Where {@link NetworkPolicyRelay} learns the control plane's currently-declared tenant-wide {@code
 * NetworkPolicySpec}s from -- the same real-source/test-double split {@code
 * com.gimle.agent.bifrost.ServiceSource} already establishes for Bifrost, kept as its own interface
 * so {@code NetworkPolicyRelayTest} can drive reconciliation off an in-memory fixture instead of a
 * real HTTP server.
 */
public interface NetworkPolicySource {

  /**
   * Every currently-declared tenant-wide {@code NetworkPolicySpec} (one whose {@code
   * deploymentNames} is absent), already projected down to the wire-friendly shape a {@link
   * com.gimle.core.protocol.ControlMessage.NetworkPoliciesUpdated} can carry. A per-deployment-
   * scoped policy is never included -- see {@link NetworkPolicyRule}'s own javadoc for why this
   * first delivery slice only threads tenant-wide restriction down to workers.
   */
  List<NetworkPolicyRule> fetchTenantWidePolicies() throws IOException, InterruptedException;
}
