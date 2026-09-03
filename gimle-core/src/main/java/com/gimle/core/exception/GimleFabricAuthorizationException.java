package com.gimle.core.exception;

/**
 * A fabric service call was rejected by the receiving worker's own tenant re-check, independent of
 * whatever filtering the calling side already applied. {@code FabricServiceRegistry}'s {@code
 * lookup}/{@code invokeByName} paths already filter candidate endpoints against a {@code
 * ServiceExport}'s {@code allowedTenantIds} before ever dialing one -- but that check runs entirely
 * in the caller, over data the caller could simply skip by dialing a {@code ServiceEndpoint}'s raw
 * address directly (obtainable from the same gossip-replicated catalog every worker receives).
 * {@code FabricServer} throwing this from its own {@code dispatch} closes that gap the same
 * "forwarded claim, independently re-checked" way {@code gimle-fafnir}/{@code gimle-muninn}/{@code
 * gimle-andvari} already re-verify a caller's identity rather than trusting it arrived pre-checked.
 */
public class GimleFabricAuthorizationException extends RuntimeException {

  private GimleFabricAuthorizationException(String message) {
    super(message);
  }

  public static GimleFabricAuthorizationException tenantNotPermitted(
      String interfaceName, String callerTenantDescription) {
    return new GimleFabricAuthorizationException(
        callerTenantDescription
            + " is not permitted to invoke "
            + interfaceName
            + " -- rejected by the receiving worker's own tenant re-check");
  }

  /**
   * The request's own written tenant claim disagrees with the tenant the connection's verified
   * client certificate carries: a caller that both holds a real worker identity and writes a
   * different tenant into the frame is forging, not confused, so the receiving worker refuses the
   * call outright rather than quietly deciding which of the two to believe.
   */
  public static GimleFabricAuthorizationException callerTenantMismatch(
      String interfaceName, String claimedDescription, String certifiedDescription) {
    return new GimleFabricAuthorizationException(
        "call to "
            + interfaceName
            + " claims "
            + claimedDescription
            + " but the connection's client certificate certifies "
            + certifiedDescription
            + " -- rejected by the receiving worker's own identity check");
  }
}
