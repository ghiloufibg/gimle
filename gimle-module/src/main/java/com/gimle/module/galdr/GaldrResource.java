package com.gimle.module.galdr;

import com.gimle.core.protocol.Json;
import com.gimle.module.lifecycle.ModuleContext;
import java.util.Map;
import java.util.Optional;

/**
 * One custom resource as an operator's tick sees it: the stored, already-defaulted spec, the
 * store's own generation counter, and the handle to report a status back. Immutable per tick -- the
 * next tick re-reads the full current set rather than this object ever updating in place.
 */
public final class GaldrResource {

  private final ModuleContext context;
  private final String kindName;
  private final String name;
  private final Optional<String> tenantId;
  private final long generation;
  private final GaldrSpec spec;
  private final Optional<Map<String, Object>> status;

  GaldrResource(
      ModuleContext context,
      String kindName,
      String name,
      Optional<String> tenantId,
      long generation,
      Map<String, Object> spec,
      Optional<Map<String, Object>> status) {
    this.context = context;
    this.kindName = kindName;
    this.name = name;
    this.tenantId = tenantId;
    this.generation = generation;
    this.spec = new GaldrSpec(spec);
    this.status = status.map(Map::copyOf);
  }

  public String kindName() {
    return kindName;
  }

  public String name() {
    return name;
  }

  public Optional<String> tenantId() {
    return tenantId;
  }

  /**
   * The store's spec-change counter -- echo it back as {@code observedGeneration} inside the status
   * this operator reports, so readers can see at a glance whether the operator has caught up with
   * the latest spec.
   */
  public long generation() {
    return generation;
  }

  public GaldrSpec spec() {
    return spec;
  }

  /** The last status any operator reported, empty until one ever has. */
  public Optional<Map<String, Object>> status() {
    return status;
  }

  /**
   * Reports {@code status} as this resource's status sub-document via {@link
   * ModuleContext#reportResourceStatus} -- last-write-wins on the server, never bumping the
   * generation. Returns the relay's own result rather than throwing: a failed report (the
   * operator's grant revoked, the resource concurrently deleted, the control plane briefly
   * unreachable) is a value the next level-triggered pass self-corrects, not an exception to crash
   * a tick over.
   */
  public ModuleContext.RelayResult reportStatus(Map<String, Object> status) {
    return context.reportResourceStatus(kindName, tenantId, name, Json.write(status));
  }
}
