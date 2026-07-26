package com.gimle.fabric.catalog;

import com.gimle.core.module.ModuleId;
import com.gimle.core.module.ServiceExport;
import com.gimle.fabric.cluster.MemberId;
import java.net.InetSocketAddress;
import java.util.Optional;

/**
 * One change to the catalog: either {@code present=true} (a module instance newly registered or
 * re-advertised an export) or {@code present=false} (a tombstone, i.e. an unregister) at a given
 * {@code version} -- the incarnation-style counter that resolves out-of-order delivery,
 * last-writer-wins by {@code (node, workerId, moduleId)} within one {@code export}'s bucket (Phase
 * 4 §5). Primarily {@link ServiceCatalog}'s own gossip-piggyback wire shape, but public (and
 * observable via {@link ServiceCatalog#onDelta}) because the node agent needs this exact shape to
 * relay a newly-learned delta down to its own supervised workers over the control channel (§5's
 * "every other agent... relays it down to its own supervised workers").
 */
public record CatalogDelta(
    ServiceExport export,
    String nodeId,
    String workerId,
    ModuleId moduleId,
    long version,
    boolean present,
    MemberId node,
    Optional<String> udsPath,
    InetSocketAddress tcpAddress) {}
