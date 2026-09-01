package com.gimle.controlplane.api;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

/**
 * An opaque page marker for {@code GET /audit}, naming one audit event by its own identity rather
 * than by a position in the trail.
 *
 * <p>The trail is a fixed-size ring: the oldest events are discarded as new decisions are recorded,
 * and new decisions arrive at the newest end while an operator is still paging towards the oldest.
 * A positional (offset) marker is wrong under both movements at once -- an append at the newest end
 * shifts every offset by one, so the next page skips rows, and an eviction at the oldest end shifts
 * them back, so it repeats rows. Anchoring on {@code eventId} instead makes each page mean "the
 * events immediately older than this exact event", which no append can disturb and which an
 * eviction can only ever invalidate outright rather than silently shift.
 *
 * <p>{@code filterFingerprint} pins the cursor to the exact filter set it was minted under. Without
 * it, an anchor missing from the current result would be ambiguous -- evicted from the trail, or
 * simply excluded by a filter the caller changed between pages -- and the two need opposite
 * answers. With the filters known to be identical, an anchor that is no longer present can only
 * have been evicted, which is what lets {@code GET /audit} say so rather than guess.
 */
record AuditCursor(String eventId, String filterFingerprint) {

  /** Guards against decoding a token minted by an incompatible earlier encoding. */
  private static final String VERSION = "v1";

  AuditCursor {
    if (eventId == null || eventId.isBlank()) {
      throw new IllegalArgumentException("cursor eventId must not be blank");
    }
    if (filterFingerprint == null) {
      throw new IllegalArgumentException("cursor filterFingerprint must not be null");
    }
  }

  /**
   * A stable, collision-free identity for one filter combination -- the four filter values escaped
   * individually and joined, so no filter value can spell out a different combination's fingerprint
   * by containing the separator itself.
   */
  static String fingerprintOf(
      Optional<String> principal,
      Optional<String> resourceKind,
      Optional<String> tenantId,
      Optional<Long> since) {
    return join(
        escape(principal.orElse("")),
        escape(resourceKind.orElse("")),
        escape(tenantId.orElse("")),
        since.map(String::valueOf).orElse(""));
  }

  String encode() {
    String payload = join(VERSION, escape(eventId), escape(filterFingerprint));
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(payload.getBytes(StandardCharsets.UTF_8));
  }

  static AuditCursor decode(String token) {
    final String payload;
    try {
      payload = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("cursor is not a valid page marker");
    }
    List<String> parts = List.of(payload.split("\\|", -1));
    if (parts.size() != 3 || !VERSION.equals(parts.get(0))) {
      throw new IllegalArgumentException("cursor is not a valid page marker");
    }
    return new AuditCursor(unescape(parts.get(1)), unescape(parts.get(2)));
  }

  private static String join(String... parts) {
    return String.join("|", parts);
  }

  private static String escape(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  private static String unescape(String value) {
    return URLDecoder.decode(value, StandardCharsets.UTF_8);
  }
}
