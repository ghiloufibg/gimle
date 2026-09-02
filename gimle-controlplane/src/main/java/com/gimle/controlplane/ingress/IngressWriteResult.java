package com.gimle.controlplane.ingress;

import com.gimle.mimir.manifest.IngressSpec;
import java.util.Optional;

/**
 * What one guarded Ingress write did, in the shape the API layer turns into a status code: {@link
 * Written} is a 200, {@link VersionConflict} a 409 carrying the state the caller must rebase on,
 * and {@link Contended} a 503 -- distinct from a conflict because nothing is wrong with the
 * submission, the lease simply never came free.
 */
public sealed interface IngressWriteResult {

  record Written(IngressSpec spec) implements IngressWriteResult {}

  record VersionConflict(int currentVersion, Optional<IngressSpec> current)
      implements IngressWriteResult {}

  record Contended() implements IngressWriteResult {}
}
