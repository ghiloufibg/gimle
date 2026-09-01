package com.gimle.agent.networkpolicy;

import com.gimle.core.tenant.NetworkPolicyRule;
import java.io.IOException;

/**
 * Where {@link NetworkPolicyRelay} learns the control plane's currently-declared {@code
 * NetworkPolicySpec}s from -- the same real-source/test-double split {@code
 * com.gimle.agent.bifrost.ServiceSource} already establishes for Bifrost, kept as its own interface
 * so {@code NetworkPolicyRelayTest} can drive reconciliation off an in-memory fixture instead of a
 * real HTTP server.
 */
public interface NetworkPolicySource {

  /**
   * Every currently-declared {@code NetworkPolicySpec}, tenant-wide and per-deployment-scoped
   * alike, already projected down to the wire-friendly shape a {@link
   * com.gimle.core.protocol.ControlMessage.NetworkPoliciesUpdated} can carry -- see {@link
   * NetworkPolicyRule#deploymentNames()} for how a scoped rule is distinguished on the receiving
   * end -- together with the tenants whose declared posture closes them to traffic no rule covers.
   */
  NetworkPolicySnapshot fetchPolicies() throws IOException, InterruptedException;
}
