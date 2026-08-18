package com.gimle.skald.directory;

import java.util.Optional;

/**
 * The name-resolution source {@link com.gimle.skald.SkaldServer} queries on every request. Kept as
 * a narrow interface (a single lookup method) so tests can swap in a plain in-memory fake instead
 * of standing up the real control-plane poller, the same way {@code ArtifactStore} lets {@code
 * AndvariServer} be tested against real storage without a network round trip.
 */
public interface ServiceDirectory {

  /**
   * Returns one live endpoint host for {@code qualifiedServiceName} (the label sequence in front of
   * {@link com.gimle.skald.dns.ServiceDnsNames#ZONE_SUFFIX}), or {@link Optional#empty()} when the
   * name is unknown or currently has no endpoints. An implementation backed by more than one
   * endpoint is free to rotate which one it returns across calls -- callers must not assume the
   * same name always resolves to the same host.
   */
  Optional<String> resolveOne(String qualifiedServiceName);
}
