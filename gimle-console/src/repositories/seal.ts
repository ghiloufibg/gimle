import type { SealingKeyRetirement, SealingKeyRotation, SealingPublicKey } from "@/types";
import { delay } from "./util";

export interface SealRepository {
  fetchPublicKey(): Promise<SealingPublicKey>;
  rotateKey(): Promise<SealingKeyRotation>;
  retireKey(keyId: number): Promise<SealingKeyRetirement>;
}

const MOCK_PUBLIC_KEY_BASE64 =
  "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAmockmockmockmockmockmockmockmock";

export class MockSealRepository implements SealRepository {
  private activeKeyId = 3;
  private readonly retired = new Set<number>();

  async fetchPublicKey(): Promise<SealingPublicKey> {
    return delay({
      sealingKeyId: this.activeKeyId,
      publicKey: MOCK_PUBLIC_KEY_BASE64,
      algorithm: "RSA-OAEP-SHA256",
    });
  }

  async rotateKey(): Promise<SealingKeyRotation> {
    this.activeKeyId += 1;
    return delay({ activeSealingKeyId: this.activeKeyId });
  }

  async retireKey(keyId: number): Promise<SealingKeyRetirement> {
    if (keyId < 0 || keyId > 255) {
      throw new Error("'keyId' must be between 0 and 255");
    }
    if (keyId === this.activeKeyId) {
      throw new Error(`cannot retire the active sealing key ${keyId}`);
    }
    this.retired.add(keyId);
    return delay({ retiredKeyId: keyId });
  }
}
