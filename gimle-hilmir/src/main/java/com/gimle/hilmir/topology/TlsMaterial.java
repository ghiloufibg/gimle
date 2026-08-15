package com.gimle.hilmir.topology;

import com.gimle.core.exception.GimleManifestException;
import java.nio.file.Path;

/**
 * Where a topology's TLS material lives on disk, for {@code LaunchPlanner} to reference when
 * building each process's own {@code -Dgimle.tls.*} flags. Deliberately not {@code gimle-core}'s
 * own {@code TlsSettings} (which names a client's own cert/key/CA file trio for building an {@link
 * javax.net.ssl.SSLContext}) -- this names a single directory holding every process's leaf under
 * the platform's own {@code PkiBootstrapMain} naming convention instead.
 */
public record TlsMaterial(Path materialDir) {

  public TlsMaterial {
    if (materialDir == null) {
      throw new GimleManifestException("tls.materialDir must be set");
    }
  }
}
