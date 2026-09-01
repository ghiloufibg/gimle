import type { SealingKeyRetirement, SealingKeyRotation, SealingPublicKey } from "@/types";
import type { SealRepository } from "@/repositories/seal";
import { requestJson, requestJsonWithBody } from "./apiClient";

/**
 * `GET /seal/public-key`, `POST /seal/rotate-key`, `POST /seal/retire-key` -- all three relayed
 * byte-for-byte to Fafnir by the control plane, so the shapes here are Fafnir's own.
 *
 * All three go through `requestJson*` rather than `requestOk`: unlike every other write endpoint
 * in this console, the two POSTs answer with a JSON body naming the key id they acted on, not the
 * literal text "ok". `rotateKey` still sends an empty JSON object, because the route requires POST
 * and reads nothing from the body -- there is no key id to choose, rotation always mints the next.
 */
export class HttpSealRepository implements SealRepository {
  async fetchPublicKey(): Promise<SealingPublicKey> {
    return requestJson<SealingPublicKey>("GET", "/seal/public-key");
  }

  async rotateKey(): Promise<SealingKeyRotation> {
    return requestJsonWithBody<SealingKeyRotation>("POST", "/seal/rotate-key", {});
  }

  async retireKey(keyId: number): Promise<SealingKeyRetirement> {
    return requestJsonWithBody<SealingKeyRetirement>("POST", "/seal/retire-key", { keyId });
  }
}
