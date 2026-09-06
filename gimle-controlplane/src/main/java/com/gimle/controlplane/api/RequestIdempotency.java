package com.gimle.controlplane.api;

import com.gimle.mimir.raft.StateMutation;
import com.gimle.mimir.store.RequestOutcomeRecord;
import com.gimle.mimir.store.StoreReader;
import com.sun.net.httpserver.HttpExchange;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The server side of request idempotency: recognises a retry of a write the caller already sent,
 * and answers it with what that write was answered with the first time.
 *
 * <p>A client can lose the answer to a write it nevertheless committed. A control-plane write is
 * proposed through the store cluster, replicated and applied; a client whose request times out
 * while that is in flight learns nothing about whether it landed. Retrying blindly is wrong for two
 * separate reasons. A write that mints something new -- a revision, a certificate -- mints a second
 * one. And a generation-guarded apply re-run against state that has moved on is not a no-op either:
 * it silently reinstates the content of an attempt the caller had already given up on, over
 * whatever landed since; while a retry sent early enough to race its own still-in-flight original
 * is refused as a generation conflict, which is worse than a duplicate, because the operator is
 * told the write failed when it succeeded and is invited to "fix" state that is already correct.
 *
 * <p>The key is the caller's own {@code X-Gimle-Request-Id} header. Only a caller can know whether
 * two requests are the same logical operation, so nothing is inferred from the body or the path,
 * and a request that carries no such header behaves exactly as it would if this class did not
 * exist. It is a header rather than a body field so it applies uniformly to POST, PUT and DELETE,
 * including requests that carry no body at all.
 *
 * <p>The receipt is cluster state, held in the replicated store, not per-replica memory: a retry
 * may be redirected to a different control-plane replica than the original, and one replica's
 * memory would be invisible to it. It is always proposed inside the same mutation batch as the
 * write it guards, so the effect and the record of what the caller was told commit together or not
 * at all.
 *
 * <p>A receipt is kept only as long as {@code ControlPlaneMain}'s own retention window, swept by
 * {@code RequestOutcomeSweeper} on the ordinary reconcile tick -- comfortably longer than a
 * client's request timeout plus any human or scripted retry after it, and short enough that the
 * table stays sized by the recent keyed-write rate. A retry that arrives after the window has
 * closed simply executes again, which is the behaviour of a caller that never keyed anything.
 *
 * <p>The principal that recorded a receipt is stored with it and re-checked on every replay. A
 * request id is opaque and client-generated, so possession of one must never be enough to read back
 * another caller's response body; a mismatch is reported as a miss and re-executes, which also
 * keeps it from confirming that the id exists at all.
 *
 * <p>Two genuinely concurrent duplicates are deliberately not serialised here. Neither one finds a
 * receipt, so both execute -- an idempotent write simply lands twice with the same effect, and for
 * the small non-idempotent set both effects land. That is a far rarer situation than the timeout
 * this exists for, which is a retry seconds or minutes after the first attempt already finished,
 * and the alternative is worse: claiming an in-flight request id distributedly would mean a second
 * round trip through the Raft log before every keyed write, doubling the cost of the common case to
 * narrow a window that hardly ever opens.
 */
final class RequestIdempotency {

  static final String REQUEST_ID_HEADER = "X-Gimle-Request-Id";
  static final String REPLAYED_HEADER = "X-Gimle-Replayed";

  /**
   * Deliberately narrow: opaque to the server, but constrained enough that an id can be logged,
   * used as a store key and echoed back in an error message without escaping concerns, and long
   * enough that a UUID or a random token fits while a trivially guessable one does not.
   */
  private static final Pattern VALID_REQUEST_ID = Pattern.compile("[A-Za-z0-9._-]{8,128}");

  private final StoreReader store;

  RequestIdempotency(StoreReader store) {
    this.store = store;
  }

  /** What a keyed write should do before executing. */
  sealed interface Check {

    /**
     * Execute the write. {@code requestId} is the id its receipt must be recorded under, or empty
     * when the caller keyed nothing and no receipt is to be written at all.
     */
    record Proceed(Optional<String> requestId) implements Check {}

    /**
     * This exact request already completed; answer it with {@code record} rather than re-running.
     */
    record Replay(RequestOutcomeRecord record) implements Check {}

    /** The id itself is unusable; the write must not run. */
    record Malformed(String reason) implements Check {}
  }

  /**
   * Decides what {@code exchange} should do, on behalf of the already-authenticated {@code
   * principalName}. Call this only after the request's own authorization has passed: an unkeyed
   * caller must never be able to learn anything from a receipt it would not have been allowed to
   * produce.
   */
  Check check(HttpExchange exchange, String principalName) {
    String header = exchange.getRequestHeaders().getFirst(REQUEST_ID_HEADER);
    if (header == null) {
      return new Check.Proceed(Optional.empty());
    }
    String requestId = header.trim();
    if (!VALID_REQUEST_ID.matcher(requestId).matches()) {
      return new Check.Malformed(
          REQUEST_ID_HEADER
              + " must be 8 to 128 characters of [A-Za-z0-9._-]; a request id is chosen by the"
              + " caller and must identify one logical operation");
    }
    return store
        .getRequestOutcome(requestId)
        .filter(record -> record.principalName().equals(principalName))
        .<Check>map(Check.Replay::new)
        .orElseGet(() -> new Check.Proceed(Optional.of(requestId)));
  }

  /**
   * The receipt to batch alongside the write, stamped with this replica's clock. The stamp travels
   * in the mutation rather than being read again at apply time, so every replica ages the same
   * receipts out at the same point.
   */
  static StateMutation.PutRequestOutcome receipt(
      String requestId, String principalName, int statusCode, String responseBody) {
    return new StateMutation.PutRequestOutcome(
        requestId, principalName, statusCode, responseBody, System.currentTimeMillis());
  }
}
