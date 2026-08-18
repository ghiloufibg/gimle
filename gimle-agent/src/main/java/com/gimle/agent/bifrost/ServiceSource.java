package com.gimle.agent.bifrost;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

/**
 * The "what services currently exist, and which endpoints back each one" query {@link BifrostProxy}
 * polls on its own interval to decide which loopback listeners to keep bound and where each one
 * should forward to. Declares the same checked-exception shape {@code AgentMain}'s own
 * control-plane calls do ({@link IOException}/{@link InterruptedException} from the underlying HTTP
 * call) so {@link HttpServiceSource} can let a failed poll propagate up to {@link BifrostProxy}'s
 * own per-tick catch rather than swallowing it silently; an implementation that never fails (e.g.
 * an in-memory test fake) simply declares neither in its override, which Java allows.
 */
public interface ServiceSource {

  /** Every service name currently known to exist, in no particular order. */
  List<String> listServiceNames() throws IOException, InterruptedException;

  /**
   * The current endpoint set for {@code serviceName}, or empty if it no longer exists (e.g. it
   * vanished between a {@link #listServiceNames()} call and this one -- a benign race {@link
   * BifrostProxy} simply retries on its next poll).
   */
  Optional<ServiceEndpoints> fetchEndpoints(String serviceName)
      throws IOException, InterruptedException;
}
