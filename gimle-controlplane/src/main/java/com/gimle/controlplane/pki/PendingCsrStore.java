package com.gimle.controlplane.pki;

import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks {@link com.gimle.core.protocol.CsrPurpose#OPERATOR_CLIENT} requests submitted to {@code
 * POST /bootstrap/csr} between submission and an existing operator's later {@code approve} call.
 * In-memory only, same reasoning as {@link BootstrapTokenRegistry} -- not Raft-replicated. This
 * endpoint is deliberately reachable without a client certificate, so entries are pruned by age on
 * access rather than kept forever; that bounds unbounded growth from spam submissions without
 * needing real rate limiting for this pass.
 */
public final class PendingCsrStore {

  private static final Duration MAX_ENTRY_AGE = Duration.ofHours(24);

  /** A pending or approved request: {@code signedCertificate} is empty until approved. */
  public record Entry(
      String csrPem, Instant submittedAt, Optional<X509Certificate> signedCertificate) {}

  private final Map<String, Entry> entries = new ConcurrentHashMap<>();

  public String submit(String csrPem) {
    prune();
    String requestId = UUID.randomUUID().toString();
    entries.put(requestId, new Entry(csrPem, Instant.now(), Optional.empty()));
    return requestId;
  }

  public Optional<Entry> get(String requestId) {
    return Optional.ofNullable(entries.get(requestId));
  }

  /** Returns {@code false} if {@code requestId} doesn't exist (never submitted, or pruned). */
  public boolean approve(String requestId, X509Certificate signedCertificate) {
    Entry[] updated = new Entry[1];
    entries.computeIfPresent(
        requestId,
        (id, existing) -> {
          Entry approved =
              new Entry(existing.csrPem(), existing.submittedAt(), Optional.of(signedCertificate));
          updated[0] = approved;
          return approved;
        });
    return updated[0] != null;
  }

  private void prune() {
    Instant cutoff = Instant.now().minus(MAX_ENTRY_AGE);
    entries.values().removeIf(entry -> entry.submittedAt().isBefore(cutoff));
  }
}
