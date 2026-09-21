import type { ResourceKind, Verb } from "@/types";
import { delay } from "./util";

/**
 * `GET /authz/can-i` -- the control plane's own self-subject access review (the `kubectl auth
 * can-i` analogue), so a write control can be hidden or disabled before an operator ever submits
 * it, rather than only surfacing the refusal after a rejected request.
 */
export interface CanIRepository {
  check(resource: ResourceKind, verb: Verb, tenant?: string): Promise<boolean>;
}

/** Mock mode has no RBAC of its own -- matches the real endpoint's own plaintext-mode carve-out
 * (`/authz/can-i` answers `true` for everything when nothing is actually gated). */
export class MockCanIRepository implements CanIRepository {
  async check(_resource: ResourceKind, _verb: Verb, _tenant?: string): Promise<boolean> {
    return delay(true);
  }
}
