import type { CanIRepository } from "@/repositories/authz";
import type { ResourceKind, Verb } from "@/types";
import { requestJson } from "./apiClient";

/**
 * `GET /authz/can-i?resource=...&verb=...[&tenant=...]` -- computed by the identical
 * authorization walk every real request goes through, so the answer can never drift from what a
 * real attempt would be allowed or refused.
 */
export class HttpCanIRepository implements CanIRepository {
  async check(resource: ResourceKind, verb: Verb, tenant?: string): Promise<boolean> {
    const query = new URLSearchParams({ resource, verb });
    if (tenant) query.set("tenant", tenant);
    const body = await requestJson<{ allowed?: boolean }>(
      "GET",
      `/authz/can-i?${query.toString()}`,
    );
    return body.allowed === true;
  }
}
