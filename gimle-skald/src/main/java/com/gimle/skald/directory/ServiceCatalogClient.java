package com.gimle.skald.directory;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

/**
 * The control plane's Service catalog, as {@link ControlPlaneServicePoller} needs to see it: the
 * full list of known services (each with its own tenant, if any), plus each one's current endpoint
 * set. A narrow interface (two read-only methods) so tests can swap in a plain in-memory fake
 * instead of a real HTTP round trip -- {@link HttpServiceCatalogClient} is the only production
 * implementation.
 */
public interface ServiceCatalogClient {

  /** Every Service the control plane currently knows about, bare name plus tenant. */
  List<ServiceListing> listServices() throws IOException, InterruptedException;

  /**
   * The current endpoint set for {@code listing}, or {@link Optional#empty()} if the control plane
   * no longer has that Service (e.g. it raced to deletion between the listing call and this one).
   *
   * <p>Takes the whole {@link ServiceListing} rather than a bare name because the control plane
   * keys a Service by {@code (tenant, name)}, not by name alone: a tenant-scoped Service addressed
   * without its tenant answers 404, which this method reports as "gone" -- indistinguishable, to
   * every caller, from a Service that really was deleted.
   */
  Optional<ServiceEndpoints> fetchEndpoints(ServiceListing listing)
      throws IOException, InterruptedException;
}
