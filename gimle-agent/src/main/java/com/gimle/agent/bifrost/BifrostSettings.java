package com.gimle.agent.bifrost;

import java.time.Duration;
import java.util.Optional;
import javax.net.ssl.SSLContext;

/**
 * How a {@link BifrostProxy} behaves beyond its data sources: the poll cadence, the NodePort-style
 * expose flag, this node's own id (what makes same-node endpoint preference possible -- empty means
 * no endpoint ever counts as local), and an optional TLS server context. When {@code tlsContext} is
 * present every listener terminates TLS and demands a cluster-CA-signed client certificate, giving
 * the proxy a verified caller tenant identity ({@code O=gimle:tenant:<id>}) to check a {@code
 * NetworkPolicySpec}'s allow list against -- the difference between enforcing a policy and failing
 * the whole listener closed (see {@link ServiceListener#forward}).
 */
public record BifrostSettings(
    Duration pollInterval,
    boolean exposeOnAllInterfaces,
    Optional<String> localNodeId,
    Optional<SSLContext> tlsContext) {

  public BifrostSettings {
    if (pollInterval == null) {
      throw new IllegalArgumentException("pollInterval must not be null");
    }
    if (localNodeId == null) {
      throw new IllegalArgumentException("localNodeId must be Optional.empty(), not null");
    }
    if (tlsContext == null) {
      throw new IllegalArgumentException("tlsContext must be Optional.empty(), not null");
    }
  }

  /** Loopback-only, no locality, plaintext -- the minimal shape most tests want. */
  public BifrostSettings(Duration pollInterval) {
    this(pollInterval, false, Optional.empty(), Optional.empty());
  }
}
