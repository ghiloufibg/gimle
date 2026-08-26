package com.gimle.skald.directory;

import java.util.List;

/**
 * The name-resolution source {@link com.gimle.skald.SkaldServer} queries on every request. Kept as
 * a narrow interface (a single lookup method) so tests can swap in a plain in-memory fake instead
 * of standing up the real control-plane poller, the same way {@code ArtifactStore} lets {@code
 * AndvariServer} be tested against real storage without a network round trip.
 */
public interface ServiceDirectory {

  /**
   * Every live endpoint of {@code qualifiedServiceName} (the label sequence in front of {@link
   * com.gimle.skald.dns.ServiceDnsNames#ZONE_SUFFIX}), or an empty list when the name is unknown or
   * currently has no endpoints. The whole set, deliberately: an {@code A} answer carries every
   * endpoint address (the headless posture -- the resolver does its own selection), and an {@code
   * SRV} answer needs every endpoint's own port, so a single-endpoint rotation would starve both.
   */
  List<HostPort> resolveAll(String qualifiedServiceName);
}
