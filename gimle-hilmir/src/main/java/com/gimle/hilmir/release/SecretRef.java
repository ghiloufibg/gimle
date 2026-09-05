package com.gimle.hilmir.release;

import com.gimle.core.hash.Sha256;
import java.nio.charset.StandardCharsets;

/**
 * A secret a release applied, recorded in its ledger by name and by a digest of its value rather
 * than by the value itself.
 *
 * <p>The ledger row is written through the plain, unencrypted {@code /config/*} surface, so a
 * plaintext value there would sit in the state store -- and in its replicated log, once per
 * revision, forever -- exactly where a vault exists to keep it out of. The digest is enough for
 * everything the ledger needs it for: it changes when the value changes, so a re-deploy of an
 * unchanged bundle still reads as converged.
 *
 * <p>Nothing is lost by not keeping the value. Fafnir versions every secret it holds, so the value
 * an earlier revision applied is still in the vault under its own version; a rollback restores
 * workloads and config and leaves secret material to the vault that owns it.
 */
public record SecretRef(String tenant, String key, String valueDigest) {

  static SecretRef of(RenderedSecretEntry entry) {
    return new SecretRef(
        entry.tenant(),
        entry.key(),
        Sha256.sha256Hex(entry.value().getBytes(StandardCharsets.UTF_8)));
  }
}
