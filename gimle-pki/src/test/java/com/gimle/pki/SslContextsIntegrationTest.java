package com.gimle.pki;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.core.tls.SslContexts;
import com.gimle.core.tls.TlsSettings;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.pkcs.PKCS10CertificationRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * End-to-end proof that {@code gimle-core}'s {@link SslContexts} and {@code gimle-pki}'s {@link
 * CertificateAuthority} actually interoperate: a real mTLS handshake between two live sockets, not
 * just "the code compiles." Lives here rather than in {@code gimle-core} because generating real
 * certificate material to test against needs Bouncy Castle, which {@code gimle-core} deliberately
 * doesn't depend on -- pulling Bouncy Castle into {@code gimle-core} would hand every other module
 * a dependency on a substantial third-party crypto library none of them ever use. Asserts on
 * handshake completion (via {@link SSLSession}) rather than an application-data round trip --
 * exercising the handshake itself is the property under test, and sending/reading app bytes on top
 * would only add an unrelated stream-lifecycle race to the test.
 */
class SslContextsIntegrationTest {

  @TempDir private Path tempDir;

  @Test
  void mutual_tls_handshake_succeeds_when_both_sides_trust_the_same_ca() throws Exception {
    CertificateAuthority ca =
        CertificateAuthority.generateSelfSignedCa(new X500Name("CN=test-ca"), Duration.ofDays(1));

    TlsSettings serverSettings = issueLeaf(ca, "server");
    TlsSettings clientSettings = issueLeaf(ca, "client");

    SSLSession serverSideSession = handshake(serverSettings, clientSettings);
    assertTrue(serverSideSession.isValid());
    assertEquals("CN=client", serverSideSession.getPeerPrincipal().getName());
  }

  @Test
  void handshake_is_rejected_when_the_client_trusts_a_different_ca_than_the_server()
      throws Exception {
    CertificateAuthority realCa =
        CertificateAuthority.generateSelfSignedCa(new X500Name("CN=real-ca"), Duration.ofDays(1));
    CertificateAuthority impostorCa =
        CertificateAuthority.generateSelfSignedCa(
            new X500Name("CN=impostor-ca"), Duration.ofDays(1));

    TlsSettings serverSettings = issueLeaf(realCa, "server");
    // A fully self-consistent client identity (its own cert really is signed by its own caFile's
    // CA) -- just a different CA than the server's: a peer presenting a cert not signed by the
    // configured CA, exactly the case mutual TLS is supposed to reject.
    TlsSettings clientSettings = issueLeaf(impostorCa, "client");

    assertThrows(IOException.class, () -> handshake(serverSettings, clientSettings));
  }

  /** Runs a real handshake over loopback TCP and returns the server side's completed session. */
  private SSLSession handshake(TlsSettings serverSettings, TlsSettings clientSettings)
      throws Exception {
    SSLContext serverContext = SslContexts.forMutualTls(serverSettings);
    SSLContext clientContext = SslContexts.forMutualTls(clientSettings);

    try (SSLServerSocket serverSocket =
        (SSLServerSocket)
            serverContext
                .getServerSocketFactory()
                .createServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
      serverSocket.setNeedClientAuth(true);

      CompletableFuture<SSLSession> serverHandshake =
          CompletableFuture.supplyAsync(
              () -> {
                try (SSLSocket accepted = (SSLSocket) serverSocket.accept()) {
                  return accepted.getSession();
                } catch (IOException e) {
                  throw new CompletionException(e);
                }
              });

      try (SSLSocket clientSocket =
          (SSLSocket)
              clientContext
                  .getSocketFactory()
                  .createSocket(InetAddress.getLoopbackAddress(), serverSocket.getLocalPort())) {
        clientSocket.startHandshake();
      }

      try {
        return serverHandshake.get(10, TimeUnit.SECONDS);
      } catch (ExecutionException e) {
        if (e.getCause() instanceof IOException ioException) {
          throw ioException;
        }
        throw e;
      }
    }
  }

  private TlsSettings issueLeaf(CertificateAuthority ca, String commonName) throws Exception {
    KeyPair keyPair = generateRsaKeyPair();
    PKCS10CertificationRequest csr =
        CertificateSigningRequests.generate(keyPair, new X500Name("CN=" + commonName));
    X509Certificate leaf = ca.signCertificateRequest(csr, Duration.ofDays(1));

    Path certFile = writePem(commonName + "-cert.pem", Pem.encodeCertificate(leaf));
    Path keyFile = writePem(commonName + "-key.pem", Pem.encodePrivateKey(keyPair.getPrivate()));
    Path caFile = writePem(commonName + "-ca.pem", Pem.encodeCertificate(ca.certificate()));

    return new TlsSettings(certFile, keyFile, caFile);
  }

  private Path writePem(String fileName, String pemContent) throws IOException {
    Path path = tempDir.resolve(fileName);
    Files.writeString(path, pemContent);
    return path;
  }

  private static KeyPair generateRsaKeyPair() throws NoSuchAlgorithmException {
    KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(2048);
    return generator.generateKeyPair();
  }
}
