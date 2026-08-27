package com.gimle.ragnarok.target.adminapi;

import com.gimle.ragnarok.RagnarokException;
import java.util.Map;

/**
 * The {@code adminApi:} document: a static map from Gimlé node id to that node's own agent-embedded
 * Admin Fault API base URL (e.g. {@code https://10.0.0.5:9500}) -- network-addressed rather than
 * SSH-addressed, the {@link com.gimle.ragnarok.target.inventory.InventorySpec#agents} analogue for
 * this target kind. Deliberately a static, hand-declared list for v1 rather than discovered
 * dynamically through the control plane's own node registry, symmetric with how {@code inventory:}
 * already requires an operator to hand-declare every machine.
 */
public record AdminApiSpec(Map<String, String> endpointByNodeId) {

  public AdminApiSpec {
    endpointByNodeId = Map.copyOf(endpointByNodeId);
    if (endpointByNodeId.isEmpty()) {
      throw new RagnarokException("adminApi must declare at least one node");
    }
  }
}
