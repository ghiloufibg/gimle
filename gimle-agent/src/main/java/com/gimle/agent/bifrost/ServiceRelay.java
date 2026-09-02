package com.gimle.agent.bifrost;

import com.gimle.core.tenant.NetworkPolicyRule;
import java.net.InetSocketAddress;
import java.util.List;

/**
 * One bound listener fronting a single Service, whatever transport it speaks. {@link BifrostProxy}
 * drives every listener through this interface and never needs to know which kind it holds: the
 * poll loop refreshes the same three things on each tick regardless.
 *
 * <p>Two implementations, and the difference between them is not cosmetic. {@link ServiceListener}
 * accepts a TCP connection and relays a byte stream in both directions for as long as either end
 * holds it open. {@link UdpServiceListener} has no connection to accept at all: it receives
 * datagrams and has to reconstruct, per client, where a reply should go -- see its own javadoc for
 * the session bookkeeping that costs.
 */
interface ServiceRelay extends AutoCloseable {

  /** Where this listener actually bound, once the port was resolved. */
  InetSocketAddress boundAddress();

  /** Replaces the live endpoint set new traffic selects over. */
  void updateEndpoints(List<ServiceEndpoint> endpoints);

  /** Whether the Service currently declares ClientIP-style session affinity. */
  void setSessionAffinity(boolean sessionAffinity);

  /**
   * The ingress-restricting policies that currently apply to this Service -- empty means
   * unrestricted. Both implementations fail closed when a policy applies and they cannot verify the
   * caller's tenant, which for a plaintext relay is always.
   */
  void setApplicableRules(List<NetworkPolicyRule> rules);

  @Override
  void close();
}
