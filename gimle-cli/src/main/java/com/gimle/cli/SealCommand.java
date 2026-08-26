package com.gimle.cli;

import com.gimle.core.protocol.Json;
import com.gimle.fafnir.secret.SealCipher;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code seal public-key [--out <path>]}, {@code seal value <plaintext> --public-key <path>
 * --tenant <id> --name <name> --key <key> [--out <path>]}, {@code seal rotate-key}, {@code seal
 * retire-key <keyId>} -- Fafnir's asymmetric sealing-key lifecycle plus the client-side sealing
 * operation itself. A distinct top-level verb rather than folded into {@link SecretCommand}, since
 * {@code public-key}/{@code value} are global/tenant-agnostic operations, the same reasoning {@code
 * artifact}/{@code audit}/{@code cert} are already their own top-level verbs for.
 *
 * <p>{@code value} is the one command in this whole CLI that never calls the control plane: it
 * reads a previously-saved {@code public-key} response and runs {@link SealCipher#seal} entirely
 * client-side. That is the point of sealing -- a caller who only holds Fafnir's public key (a CI
 * pipeline, a value committed to a config repo ahead of deploy) can produce a value only Fafnir's
 * matching private key can ever recover, with no live authenticated session at seal time. The
 * resulting envelope is inert JSON; {@code secretmap seal --from-sealed} is what actually submits
 * it.
 *
 * <p>{@code rotate-key}/{@code retire-key} mirror {@code secret rotate-key}/{@code retire-key}'s
 * result-printing shape exactly. Retiring a sealing key is destructive the same way retiring a
 * {@code secret} key is, but strictly less so: it only blocks unwrapping sealed blobs not yet
 * committed -- a SecretMap value already applied through {@code secretmap seal} was re-encrypted
 * under Fafnir's own current symmetric key at commit time and never stored in sealed form, so it is
 * unaffected by a later sealing-key retirement.
 */
public final class SealCommand {

  private final ControlPlaneClient client;
  private final OutputFormat.Kind output;
  private final PrintStream out;

  public SealCommand(ControlPlaneClient client, OutputFormat.Kind output, PrintStream out) {
    this.client = client;
    this.output = output;
    this.out = out;
  }

  public void run(List<String> args) {
    if (args.isEmpty()) {
      throw new CliException(usage());
    }
    String verb = args.get(0);
    List<String> rest = args.subList(1, args.size());
    switch (verb) {
      case "public-key" -> publicKey(rest);
      case "value" -> value(rest);
      case "rotate-key" -> rotateKey();
      case "retire-key" -> retireKey(rest);
      default -> throw new CliException(usage());
    }
  }

  private void publicKey(List<String> args) {
    Flags flags = Flags.parse(args, Set.of());
    String response = client.expectSuccess(client.get("/seal/public-key"));
    String outFile = flags.getOrDefault("--out", null);
    if (outFile != null) {
      writeFile(outFile, response);
      OutputFormat.printResult(
          output,
          resultBody("saved", Json.asObject(Json.parse(response))),
          "saved to " + outFile,
          out);
      return;
    }
    OutputFormat.printObject(output, Json.asObject(Json.parse(response)), out);
  }

  private void value(List<String> args) {
    if (args.isEmpty()) {
      throw new CliException(
          "seal value requires <plaintext> --public-key <path> --tenant <id> --name <name> --key"
              + " <key>");
    }
    String plaintext = args.get(0);
    Flags flags = Flags.parse(args.subList(1, args.size()), Set.of());
    Path publicKeyFile = Path.of(flags.get("--public-key"));
    String tenantId = flags.get("--tenant");
    String name = flags.get("--name");
    String key = flags.get("--key");
    String outFile = flags.getOrDefault("--out", null);

    Map<String, Object> publicKeyResponse = Json.asObject(Json.parse(readFile(publicKeyFile)));
    int sealingKeyId = ((Number) publicKeyResponse.get("sealingKeyId")).intValue();
    PublicKey publicKey = decodePublicKey((String) publicKeyResponse.get("publicKey"));

    SealCipher.SealedEnvelope envelope =
        SealCipher.seal(
            plaintext.getBytes(StandardCharsets.UTF_8),
            publicKey,
            (byte) sealingKeyId,
            SealCipher.aadFor(tenantId, name, key));

    Map<String, Object> envelopeJson = new LinkedHashMap<>();
    envelopeJson.put("sealingKeyId", Byte.toUnsignedInt(envelope.sealingKeyId()));
    envelopeJson.put("wrappedKey", Base64.getEncoder().encodeToString(envelope.wrappedDataKey()));
    envelopeJson.put("ciphertext", Base64.getEncoder().encodeToString(envelope.ciphertext()));
    String envelopeText = Json.write(envelopeJson);

    if (outFile != null) {
      writeFile(outFile, envelopeText);
      OutputFormat.printResult(
          output, resultBody("sealed", envelopeJson), "sealed value saved to " + outFile, out);
      return;
    }
    OutputFormat.printObject(output, envelopeJson, out);
  }

  private void rotateKey() {
    String response = client.expectSuccess(client.post("/seal/rotate-key", ""));
    Object activeSealingKeyId = Json.asObject(Json.parse(response)).get("activeSealingKeyId");
    Map<String, Object> resultBody = new LinkedHashMap<>();
    resultBody.put("result", "rotated");
    resultBody.put("kind", "sealing-key");
    resultBody.put("activeSealingKeyId", activeSealingKeyId);
    OutputFormat.printResult(
        output, resultBody, "sealing key rotated (active key id " + activeSealingKeyId + ")", out);
  }

  private void retireKey(List<String> args) {
    if (args.isEmpty()) {
      throw new CliException("seal retire-key requires <keyId>");
    }
    int keyId = parseKeyId(args.get(0));
    String response =
        client.expectSuccess(client.post("/seal/retire-key", Json.write(Map.of("keyId", keyId))));
    Object retiredKeyId = Json.asObject(Json.parse(response)).get("retiredKeyId");
    Map<String, Object> resultBody = new LinkedHashMap<>();
    resultBody.put("result", "retired");
    resultBody.put("kind", "sealing-key");
    resultBody.put("retiredKeyId", retiredKeyId);
    OutputFormat.printResult(output, resultBody, "sealing key " + retiredKeyId + " retired", out);
  }

  private static int parseKeyId(String raw) {
    try {
      return Integer.parseInt(raw);
    } catch (NumberFormatException e) {
      throw new CliException("keyId must be an integer, got: " + raw);
    }
  }

  private static PublicKey decodePublicKey(String base64Der) {
    try {
      byte[] der = Base64.getDecoder().decode(base64Der);
      return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));
    } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
      throw new IllegalStateException("could not decode sealing public key", e);
    }
  }

  private static Map<String, Object> resultBody(String result, Map<String, Object> payload) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("result", result);
    body.put("kind", "seal");
    body.putAll(payload);
    return body;
  }

  private static String readFile(Path path) {
    try {
      return Files.readString(path, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException("could not read file: " + path, e);
    }
  }

  private static void writeFile(String path, String content) {
    try {
      Files.writeString(Path.of(path), content, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException("could not write file: " + path, e);
    }
  }

  static String usage() {
    return """
        usage: gimle seal <verb> [args]

        verbs:
          public-key [--out <path>]
          value <plaintext> --public-key <path> --tenant <id> --name <name> --key <key> [--out <path>]
          rotate-key
          retire-key <keyId>
        """;
  }
}
