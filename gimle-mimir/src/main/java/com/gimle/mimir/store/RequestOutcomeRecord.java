package com.gimle.mimir.store;

/**
 * The receipt for one already-completed control-plane write that carried a caller-supplied request
 * id: the status code and response body that write was answered with, plus the name of the
 * principal it was answered for.
 *
 * <p>Exists because a client can lose the answer to a write it nevertheless committed -- a request
 * that times out client-side may already be replicated and applied, and the timeout itself carries
 * no information either way. Remembering the answer rather than merely that the id was seen is what
 * lets a retry be completed rather than merely refused: the caller gets the original outcome, so a
 * write that mints something new (a revision, a certificate) is never minted twice, and one guarded
 * by a compare-and-set precondition never reports a false conflict against a generation its own
 * first attempt already moved.
 *
 * <p>{@code principalName} is stored so a replay can be refused to anyone but the caller that
 * recorded it -- a request id is opaque and client-generated, so possession of one must never be
 * enough to read back somebody else's response body.
 *
 * <p>{@code recordedAtEpochMilli} is the stamp taken by the replica that proposed the write, not
 * the clock of whichever replica applies it, so every replica expires the identical set of receipts
 * when the retention sweep runs.
 */
public record RequestOutcomeRecord(
    String requestId,
    String principalName,
    int statusCode,
    String responseBody,
    long recordedAtEpochMilli) {

  public RequestOutcomeRecord {
    if (requestId == null || requestId.isBlank()) {
      throw new IllegalArgumentException("requestId must not be blank");
    }
    if (principalName == null || principalName.isBlank()) {
      throw new IllegalArgumentException("principalName must not be blank");
    }
    if (statusCode < 100 || statusCode > 599) {
      throw new IllegalArgumentException("statusCode must be a valid HTTP status: " + statusCode);
    }
    if (responseBody == null) {
      throw new IllegalArgumentException("responseBody must not be null");
    }
    if (recordedAtEpochMilli <= 0) {
      throw new IllegalArgumentException(
          "recordedAtEpochMilli must be positive: " + recordedAtEpochMilli);
    }
  }
}
