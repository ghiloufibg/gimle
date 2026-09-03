package com.gimle.controlplane.api;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

/**
 * An opaque page marker for the cluster-wide {@code GET /events} read, naming one lifecycle event
 * by its own identity rather than by a position in the merged timeline -- the same reasoning {@link
 * AuditCursor} already establishes for {@code GET /audit}: the underlying per-instance timelines
 * each prune their own oldest entries independently as new ones arrive, so a positional (offset)
 * marker would drift under either movement. Anchoring on {@link
 * com.gimle.core.protocol.InstanceEvent#id()} instead -- generated once at the point of occurrence
 * and stable across every hop, per that record's own javadoc -- makes each page mean "the events
 * immediately older than this exact event" regardless of what else changed in between.
 *
 * <p>{@code filterFingerprint} pins the cursor to the exact {@code tenant}/{@code since} filters it
 * was minted under, the same collision-free escape-and-join scheme {@link
 * AuditCursor#fingerprintOf} uses -- without it, an anchor missing from a re-run query would be
 * ambiguous between "pruned" and "excluded by a filter the caller changed between pages".
 */
record InstanceEventCursor(String eventId, String filterFingerprint) {

  /** Guards against decoding a token minted by an incompatible earlier encoding. */
  private static final String VERSION = "v1";

  InstanceEventCursor {
    if (eventId == null || eventId.isBlank()) {
      throw new IllegalArgumentException("cursor eventId must not be blank");
    }
    if (filterFingerprint == null) {
      throw new IllegalArgumentException("cursor filterFingerprint must not be null");
    }
  }

  static String fingerprintOf(Optional<String> tenantId, Optional<Long> since) {
    return join(escape(tenantId.orElse("")), since.map(String::valueOf).orElse(""));
  }

  String encode() {
    String payload = join(VERSION, escape(eventId), escape(filterFingerprint));
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(payload.getBytes(StandardCharsets.UTF_8));
  }

  static InstanceEventCursor decode(String token) {
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
    return new InstanceEventCursor(unescape(parts.get(1)), unescape(parts.get(2)));
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
