import { toast } from "sonner";

import {
  ApiError,
  SESSION_EXPIRED_MESSAGE,
  SessionExpiredError,
} from "@/repositories/http/apiClient";

/** The control plane's own body for a denied-by-RBAC request; it carries nothing an operator can
 * act on, so it is dropped rather than appended to the sentence below. */
const BARE_FORBIDDEN_BODY = "forbidden";

const FORBIDDEN_MESSAGE = "You don't have permission to do that.";

export function isSessionExpired(error: unknown): boolean {
  return error instanceof SessionExpiredError;
}

/** The sentence to put in front of an operator for a failed request: plain language for the two
 * failures that are about who they are (401, 403), and the underlying message for everything
 * else, which is a genuine fault whose detail is worth showing. */
export function describeApiError(error: unknown): string {
  if (isSessionExpired(error)) return SESSION_EXPIRED_MESSAGE;
  if (error instanceof ApiError && error.status === 403) {
    const detail = error.body.trim();
    return detail && detail !== BARE_FORBIDDEN_BODY
      ? `${FORBIDDEN_MESSAGE} (${detail})`
      : FORBIDDEN_MESSAGE;
  }
  if (error instanceof Error) return error.message;
  return String(error);
}

/**
 * Shows a failed request to the operator, and returns whether anything was shown.
 *
 * An expired session shows nothing: it is already being handled globally -- local auth state is
 * cleared, the router bounces to /login, and that screen says why. A toast on the way out would be
 * a second explanation for one event, thrown at a screen that is about to be replaced. Every other
 * failure, a 403 included, is surfaced in place, where the action the operator just took is.
 */
export function notifyApiError(error: unknown): boolean {
  if (isSessionExpired(error)) return false;
  toast.error(describeApiError(error));
  return true;
}

/** The same call for a store that renders its failure inline instead of toasting it: `null` for an
 * expired session, because that screen is being replaced by the sign-in screen and an error banner
 * flashing on the way out explains nothing. */
export function storeErrorMessage(error: unknown): string | null {
  return isSessionExpired(error) ? null : describeApiError(error);
}
