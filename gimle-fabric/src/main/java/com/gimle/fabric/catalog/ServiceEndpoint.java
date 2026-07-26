package com.gimle.fabric.catalog;

import com.gimle.core.module.ModuleId;
import com.gimle.core.module.ServiceExport;
import com.gimle.fabric.cluster.MemberId;
import java.net.InetSocketAddress;
import java.util.Optional;

/**
 * One module instance's advertisement of one exported service, as tracked by {@link ServiceCatalog}
 * (Phase 4 §5). Extends the design's literal record with the two dialable addresses a caller
 * actually needs to reach it -- {@code udsPath} for the same-machine tier, {@code tcpAddress} for
 * the cross-machine tier (§7's "the node agent... publishes this map to its own GossipMember as
 * part of each ServiceEndpoint's address" only makes sense once the endpoint carries a real
 * address, not just a node id). {@code udsPath} is empty for an endpoint this node only ever
 * learned about remotely, since a Unix domain socket path is meaningless off its own machine.
 */
public record ServiceEndpoint(
    MemberId node,
    String workerId,
    ModuleId moduleId,
    ServiceExport export,
    boolean ready,
    Optional<String> udsPath,
    InetSocketAddress tcpAddress) {

  public ServiceEndpoint {
    if (node == null) {
      throw new IllegalArgumentException("node must not be null");
    }
    if (workerId == null || workerId.isBlank()) {
      throw new IllegalArgumentException("workerId must not be blank");
    }
    if (moduleId == null) {
      throw new IllegalArgumentException("moduleId must not be null");
    }
    if (export == null) {
      throw new IllegalArgumentException("export must not be null");
    }
    if (udsPath == null) {
      throw new IllegalArgumentException("udsPath must not be null (use Optional.empty())");
    }
    if (tcpAddress == null) {
      throw new IllegalArgumentException("tcpAddress must not be null");
    }
  }
}
