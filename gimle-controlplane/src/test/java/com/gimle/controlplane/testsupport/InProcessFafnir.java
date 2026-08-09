package com.gimle.controlplane.testsupport;

import com.gimle.controlplane.fafnir.FafnirClient;
import com.gimle.fafnir.FafnirCrypto;
import com.gimle.fafnir.FafnirServer;
import com.gimle.mimir.rpc.StoreClient;
import java.io.IOException;
import java.nio.file.Path;

/**
 * A real, in-process Fafnir replica -- real {@link FafnirCrypto}, real {@link FafnirServer} bound
 * to a loopback ephemeral port -- backing a ready {@link FafnirClient}, the same "real but
 * embedded" fixture shape {@link InProcessStore} already established for {@code gimle-mimir}. Every
 * {@code ApiServer} test that touches encrypted config now needs one of these, since {@code
 * ApiServer} itself no longer does any crypto in-process (design doc Phase A) -- a genuine HTTP
 * round trip happens on every encrypt/decrypt/rotate call, just to a same-JVM, loopback-bound
 * server rather than a separately-deployed process.
 */
public final class InProcessFafnir implements AutoCloseable {

  private final FafnirServer server;
  private final FafnirClient client;

  private InProcessFafnir(FafnirServer server, FafnirClient client) {
    this.server = server;
    this.client = client;
  }

  public static InProcessFafnir start(StoreClient storeClient, Path secretKeyFilePath)
      throws IOException {
    FafnirCrypto crypto = new FafnirCrypto(storeClient, secretKeyFilePath);
    FafnirServer server = new FafnirServer(crypto, 0);
    server.start();
    // "localhost", not "127.0.0.1": in TLS mode the client performs real hostname verification
    // against the server cert's SAN, and every TLS-mode caller of this fixture issues leaves with
    // "localhost" as the SAN (matching how a real deployment's own loopback dev setup would be
    // named), not the bare IP literal.
    FafnirClient client = new FafnirClient("localhost:" + server.port());
    return new InProcessFafnir(server, client);
  }

  public FafnirClient client() {
    return client;
  }

  @Override
  public void close() {
    client.close();
    server.close();
  }
}
