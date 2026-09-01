package com.gimle.skald.directory;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * The name-resolution source {@link com.gimle.skald.SkaldServer} queries on every request. Kept as
 * a narrow interface so tests can swap in a plain in-memory fake instead of standing up the real
 * control-plane poller, the same way {@code ArtifactStore} lets {@code AndvariServer} be tested
 * against real storage without a network round trip.
 */
public interface ServiceDirectory {

  /**
   * Every live endpoint of {@code qualifiedServiceName} (the label sequence in front of {@link
   * com.gimle.skald.dns.ServiceDnsNames#ZONE_SUFFIX}), or {@link Optional#empty()} when no Service
   * of that name is known at all. The whole set, deliberately: an {@code A} answer carries every
   * endpoint address (the headless posture -- the resolver does its own selection), and an {@code
   * SRV} answer needs every endpoint's own port, so a single-endpoint rotation would starve both.
   *
   * <p>A present-but-empty list and an absent {@link Optional} are two genuinely different answers,
   * not one condition spelled two ways: a declared Service momentarily backed by zero live
   * instances (mid-rollout, or scaled to zero) exists and is empty, while a misspelled or
   * never-declared name does not exist. Collapsing the first into the second would answer {@code
   * NXDOMAIN} for a Service an operator can see in the control plane's own catalog, which reads as
   * "you got the name wrong" exactly when the name was right.
   */
  Optional<List<HostPort>> resolveAll(String qualifiedServiceName);

  /**
   * How long it has been since a poll last actually refreshed this directory's data, measured from
   * construction if no poll has ever succeeded yet. This is what lets {@link
   * com.gimle.skald.SkaldServer} tell "one missed poll" apart from "the control plane has been down
   * for an hour" -- a brief gap is unremarkable, a long one means the endpoints on file may no
   * longer be correct.
   */
  Duration timeSinceLastSuccess();

  /**
   * How many polls have failed in a row since the last successful one (reset to zero by a success).
   * Exposed alongside {@link #timeSinceLastSuccess} because the two answer different operator
   * questions -- one is "how long has our view been wrong for," the other is "is this actively
   * still failing or did it just recover."
   */
  int consecutiveFailures();
}
