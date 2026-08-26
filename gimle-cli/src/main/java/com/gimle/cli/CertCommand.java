package com.gimle.cli;

import com.gimle.core.protocol.CsrPurpose;
import com.gimle.core.protocol.CsrRequestStatus;
import com.gimle.core.protocol.Json;
import com.gimle.core.tls.TlsSettings;
import com.gimle.core.tls.TransportProtocol;
import com.gimle.pki.CertificateSigningRequests;
import com.gimle.pki.Pem;
import com.gimle.pki.RenewalSchedule;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.pkcs.PKCS10CertificationRequest;

/**
 * {@code cert token create}, {@code cert request}, {@code cert status}, {@code cert approve},
 * {@code cert renew} -- the operator-facing half of the node join and certificate rotation flows.
 */
public final class CertCommand {

  private final String serverAddress;
  private final OutputFormat.Kind output;
  private final PrintStream out;

  /**
   * Deliberately takes a server address rather than a shared {@link ControlPlaneClient}: {@code
   * request}/{@code status} need a trust-only client (no certificate exists yet), while {@code
   * token}/{@code approve}/{@code renew} need a fully-authenticated one -- each subcommand builds
   * exactly the client it needs rather than one being constructed up front for all of them (which
   * would fail before ever reaching this class, since the default {@link ControlPlaneClient}
   * constructor requires {@code gimle.tls.certFile}/{@code keyFile} to already exist).
   */
  public CertCommand(String serverAddress, OutputFormat.Kind output, PrintStream out) {
    this.serverAddress = serverAddress;
    this.output = output;
    this.out = out;
  }

  /**
   * Best-effort, called once per CLI invocation regardless of verb (see {@link GimleCli#dispatch}):
   * {@code gimle-cli} never renews silently -- it only warns, leaving the explicit {@code cert
   * renew} call as the user's own prompted action. Never blocks or fails a command over a
   * renewal-check problem; TLS not being configured at all is the overwhelmingly common case.
   */
  static void warnIfRenewalDue(PrintStream out) {
    if (TransportProtocol.fromConfig() == TransportProtocol.PLAINTEXT) {
      return;
    }
    String certFileProperty = System.getProperty("gimle.tls.certFile");
    if (certFileProperty == null || certFileProperty.isBlank()) {
      return;
    }
    Path certFile = Path.of(certFileProperty);
    if (!Files.isRegularFile(certFile)) {
      return;
    }
    try {
      X509Certificate certificate =
          Pem.decodeCertificate(Files.readString(certFile, StandardCharsets.US_ASCII));
      if (RenewalSchedule.of(certificate).isDue(Instant.now())) {
        out.println("warning: your credential is due for renewal; run 'gimle cert renew'");
      }
    } catch (IOException | RuntimeException e) {
      // Best-effort only -- never block a command over a renewal-check failure.
    }
  }

  public void run(List<String> args) {
    if (args.isEmpty()) {
      throw new CliException(usage());
    }
    String sub = args.get(0);
    List<String> rest = args.subList(1, args.size());
    switch (sub) {
      case "token" -> tokenCreate(rest);
      case "request" -> request(rest);
      case "status" -> status(rest);
      case "approve" -> approve(rest);
      case "renew" -> renew(rest);
      case "revoke" -> setRevocation(rest, true);
      case "unrevoke" -> setRevocation(rest, false);
      case "revocations" -> listRevocations();
      default -> throw new CliException(usage());
    }
  }

  /**
   * {@code gimle cert revoke|unrevoke <serialHex>} -- {@code PUT}/{@code DELETE
   * /certificates/revoked/{serial}}. The serial is the hex form {@code openssl x509 -serial}
   * prints; every process that authenticates the revoked leaf refuses it from its next request on.
   */
  private void setRevocation(List<String> args, boolean revoke) {
    if (args.size() != 1) {
      throw new CliException(
          "usage: gimle cert " + (revoke ? "revoke" : "unrevoke") + " <serialHex>");
    }
    String serial = args.get(0);
    ControlPlaneClient client = new ControlPlaneClient(serverAddress);
    String path = "/certificates/revoked/" + serial;
    ApiResponse response = revoke ? client.put(path, "") : client.delete(path);
    Map<String, Object> result = Json.asObject(Json.parse(client.expectSuccess(response)));
    OutputFormat.printResult(
        output,
        result,
        (revoke ? "revoked" : "unrevoked") + " certificate serial " + result.get("serial"),
        out);
  }

  /** {@code gimle cert revocations} -- {@code GET /certificates/revoked}. */
  private void listRevocations() {
    ControlPlaneClient client = new ControlPlaneClient(serverAddress);
    Map<String, Object> result =
        Json.asObject(Json.parse(client.expectSuccess(client.get("/certificates/revoked"))));
    OutputFormat.printResult(
        output, result, "revoked serials: " + result.get("revokedSerials"), out);
  }

  /** {@code gimle cert token create [--ttl <duration>]} -- {@code POST /bootstrap/tokens}. */
  private void tokenCreate(List<String> args) {
    if (args.isEmpty() || !"create".equals(args.get(0))) {
      throw new CliException("usage: gimle cert token create [--ttl <duration>]");
    }
    Flags flags = Flags.parse(args.subList(1, args.size()), Set.of());
    Duration ttl = parseDuration(flags.getOrDefault("--ttl", "1h"));

    Map<String, Object> body = new LinkedHashMap<>();
    body.put("ttlSeconds", ttl.toSeconds());
    ControlPlaneClient client = new ControlPlaneClient(serverAddress);
    Map<String, Object> result =
        Json.asObject(
            Json.parse(client.expectSuccess(client.post("/bootstrap/tokens", Json.write(body)))));
    OutputFormat.printResult(output, result, "bootstrap token: " + result.get("token"), out);
  }

  /**
   * {@code gimle cert request --purpose operator|node --out-cert <path> --out-key <path>} --
   * generates a key pair and CSR locally, submits it unauthenticated (no client cert exists yet),
   * and writes the private key immediately regardless of outcome -- it never travels over the wire,
   * so it must be saved now, not reconstructed later. The certificate is written now only if
   * synchronously approved; otherwise {@code cert status} writes it once an operator approves.
   */
  private void request(List<String> args) {
    Flags flags = Flags.parse(args, Set.of());
    CsrPurpose purpose = parsePurpose(flags.getOrDefault("--purpose", "operator"));
    Path outCert = Path.of(flags.get("--out-cert"));
    Path outKey = Path.of(flags.get("--out-key"));
    String commonName =
        flags.getOrDefault("--common-name", System.getProperty("user.name", "operator"));

    try {
      KeyPair keyPair = generateRsaKeyPair();
      PKCS10CertificationRequest csr =
          CertificateSigningRequests.generate(keyPair, new X500Name("CN=" + commonName));
      Files.writeString(
          outKey, Pem.encodePrivateKey(keyPair.getPrivate()), StandardCharsets.US_ASCII);

      Map<String, Object> submission = new LinkedHashMap<>();
      submission.put("purpose", purpose.name());
      submission.put("csrPem", Pem.encodeCsr(csr));

      ControlPlaneClient trustOnlyClient = ControlPlaneClient.trustOnly(serverAddress);
      Map<String, Object> result =
          Json.asObject(
              Json.parse(
                  trustOnlyClient.expectSuccess(
                      trustOnlyClient.post("/bootstrap/csr", Json.write(submission)))));
      CsrRequestStatus status = CsrRequestStatus.valueOf((String) result.get("status"));
      if (status == CsrRequestStatus.APPROVED) {
        Files.writeString(
            outCert, (String) result.get("certificatePem"), StandardCharsets.US_ASCII);
        OutputFormat.printResult(output, result, "certificate issued: " + outCert, out);
        return;
      }
      Object requestId = result.get("requestId");
      OutputFormat.printResult(
          output,
          result,
          "request pending approval (id "
              + requestId
              + "); private key written to "
              + outKey
              + "; once approved run: gimle cert status "
              + requestId
              + " --out-cert "
              + outCert,
          out);
    } catch (IOException e) {
      throw new CliException("failed to write certificate material: " + e.getMessage(), e);
    }
  }

  /**
   * {@code gimle cert status <request-id> --out-cert <path>} -- polls {@code GET
   * /bootstrap/csr/{id}}.
   */
  private void status(List<String> args) {
    if (args.isEmpty()) {
      throw new CliException("usage: gimle cert status <request-id> --out-cert <path>");
    }
    String requestId = args.get(0);
    Flags flags = Flags.parse(args.subList(1, args.size()), Set.of());
    Path outCert = Path.of(flags.get("--out-cert"));

    ControlPlaneClient trustOnlyClient = ControlPlaneClient.trustOnly(serverAddress);
    Map<String, Object> result =
        Json.asObject(
            Json.parse(
                trustOnlyClient.expectSuccess(trustOnlyClient.get("/bootstrap/csr/" + requestId))));
    CsrRequestStatus status = CsrRequestStatus.valueOf((String) result.get("status"));
    if (status != CsrRequestStatus.APPROVED) {
      OutputFormat.printResult(output, result, "still pending approval", out);
      return;
    }
    try {
      Files.writeString(outCert, (String) result.get("certificatePem"), StandardCharsets.US_ASCII);
    } catch (IOException e) {
      throw new CliException("failed to write certificate: " + e.getMessage(), e);
    }
    OutputFormat.printResult(output, result, "certificate issued: " + outCert, out);
  }

  /**
   * {@code gimle cert approve <request-id>} -- {@code POST /bootstrap/csr/{id}/approve}, using this
   * invocation's own configured mTLS identity as the approving operator's credential.
   */
  private void approve(List<String> args) {
    if (args.isEmpty()) {
      throw new CliException("usage: gimle cert approve <request-id>");
    }
    String requestId = args.get(0);
    ControlPlaneClient client = new ControlPlaneClient(serverAddress);
    client.expectSuccess(client.post("/bootstrap/csr/" + requestId + "/approve", ""));
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("result", "approved");
    result.put("requestId", requestId);
    OutputFormat.printResult(output, result, "request " + requestId + " approved", out);
  }

  /**
   * {@code gimle cert renew [--force]} -- the one explicitly-prompted action {@link
   * #warnIfRenewalDue} points a user at; renews only if actually due, unless {@code --force}.
   */
  private void renew(List<String> args) {
    Flags flags = Flags.parse(args, Set.of("--force"));
    try {
      TlsSettings settings = TlsSettings.fromConfig();
      X509Certificate current =
          Pem.decodeCertificate(Files.readString(settings.certFile(), StandardCharsets.US_ASCII));
      if (!flags.isSet("--force") && !RenewalSchedule.of(current).isDue(Instant.now())) {
        out.println("certificate is not yet due for renewal (use --force to renew anyway)");
        return;
      }
      KeyPair keyPair = generateRsaKeyPair();
      // See OwnCertificateRotator's identical fix: X500Name.getInstance(...getEncoded()) preserves
      // the certificate's own RDN order, unlike reconstructing from the RFC 2253 string rendering,
      // which reorders a multi-RDN subject and would fail the server's byte-for-byte subject check.
      X500Name subject = X500Name.getInstance(current.getSubjectX500Principal().getEncoded());
      PKCS10CertificationRequest csr = CertificateSigningRequests.generate(keyPair, subject);
      Map<String, Object> submission = new LinkedHashMap<>();
      submission.put("purpose", CsrPurpose.OPERATOR_CLIENT.name());
      submission.put("csrPem", Pem.encodeCsr(csr));

      ControlPlaneClient client = new ControlPlaneClient(serverAddress);
      Map<String, Object> result =
          Json.asObject(
              Json.parse(
                  client.expectSuccess(client.post("/bootstrap/csr", Json.write(submission)))));
      Files.writeString(
          settings.certFile(), (String) result.get("certificatePem"), StandardCharsets.US_ASCII);
      Files.writeString(
          settings.keyFile(),
          Pem.encodePrivateKey(keyPair.getPrivate()),
          StandardCharsets.US_ASCII);
      out.println("certificate renewed");
    } catch (IOException e) {
      throw new CliException("certificate renewal failed: " + e.getMessage(), e);
    }
  }

  private static CsrPurpose parsePurpose(String value) {
    return switch (value) {
      case "operator" -> CsrPurpose.OPERATOR_CLIENT;
      case "node" -> CsrPurpose.NODE_CLIENT;
      default ->
          throw new CliException("unknown --purpose: " + value + " (expected operator or node)");
    };
  }

  private static Duration parseDuration(String text) {
    if (text.isEmpty()) {
      throw new CliException("invalid duration: (empty)");
    }
    if (text.matches("\\d+")) {
      return Duration.ofSeconds(Long.parseLong(text));
    }
    char unit = text.charAt(text.length() - 1);
    long amount;
    try {
      amount = Long.parseLong(text.substring(0, text.length() - 1));
    } catch (NumberFormatException e) {
      throw new CliException("invalid duration: " + text + " (expected e.g. 30m, 1h, 3600s)");
    }
    return switch (unit) {
      case 's' -> Duration.ofSeconds(amount);
      case 'm' -> Duration.ofMinutes(amount);
      case 'h' -> Duration.ofHours(amount);
      case 'd' -> Duration.ofDays(amount);
      default ->
          throw new CliException("invalid duration unit: " + unit + " (expected s, m, h, or d)");
    };
  }

  private static KeyPair generateRsaKeyPair() {
    try {
      KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
      generator.initialize(2048);
      return generator.generateKeyPair();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("RSA key pair generation unavailable", e);
    }
  }

  private static String usage() {
    return """
        usage: gimle cert token create [--ttl <duration>]
               gimle cert request --purpose operator|node --out-cert <path> --out-key <path>
               gimle cert status <request-id> --out-cert <path>
               gimle cert approve <request-id>
               gimle cert renew [--force]
               gimle cert revoke <serialHex>
               gimle cert unrevoke <serialHex>
               gimle cert revocations""";
  }
}
