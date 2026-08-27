package com.gimle.ragnarok.target.endpoint;

import com.gimle.core.tls.TlsSettings;
import com.gimle.core.tls.TransportProtocol;
import com.gimle.ragnarok.RagnarokException;
import com.gimle.ragnarok.config.YamlParsing;
import com.gimle.ragnarok.target.adminapi.AdminApiSpec;
import com.gimle.ragnarok.target.adminapi.AdminApiSpecParser;
import com.gimle.ragnarok.target.inventory.InventorySpec;
import com.gimle.ragnarok.target.inventory.InventorySpecParser;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Parses a target document into a {@link TargetSpec}, following the same shape as {@link
 * com.gimle.ragnarok.surtr.SurtrWorkloadParser} and {@link
 * com.gimle.holmgang.topology.ClusterTopologyParser} (not depended on here, but the {@code
 * transport}/{@code tls} field shape is deliberately identical so an operator who already knows a
 * Holmgang topology document recognizes this immediately).
 */
public final class TargetSpecParser {

  private TargetSpecParser() {}

  public static TargetSpec parse(final InputStream yamlContent) {
    final Object raw;
    try {
      raw = new Yaml(new SafeConstructor(new LoaderOptions())).load(yamlContent);
    } catch (final RuntimeException e) {
      throw new RagnarokException("malformed YAML in target document", e);
    }
    if (!(raw instanceof Map<?, ?> root)) {
      throw new RagnarokException("target document must contain a YAML mapping at the root");
    }
    return parseRoot(root);
  }

  public static TargetSpec resolve(final String path) {
    try (InputStream in = Files.newInputStream(Path.of(path))) {
      return parse(in);
    } catch (final IOException e) {
      throw new RagnarokException("failed reading target file: " + path, e);
    }
  }

  private static TargetSpec parseRoot(final Map<?, ?> root) {
    final var controlPlaneBaseUrls = YamlParsing.stringList(root, "controlPlaneBaseUrls");
    final var storeClientEndpoints = YamlParsing.stringList(root, "storeClientEndpoints");
    final var muninnBaseUrls = YamlParsing.stringList(root, "muninnBaseUrls");
    final var andvariBaseUrls = YamlParsing.stringList(root, "andvariBaseUrls");
    final TransportProtocol transport = parseTransport(root);
    final Optional<TlsSettings> tls = parseTls(root);
    final Optional<InventorySpec> inventory = parseInventory(root);
    final Optional<AdminApiSpec> adminApi = parseAdminApi(root);
    final Path workDir =
        YamlParsing.optionalString(root, "workDir")
            .map(Path::of)
            .orElseGet(TargetSpecParser::defaultWorkDir);
    return new TargetSpec(
        controlPlaneBaseUrls,
        storeClientEndpoints,
        muninnBaseUrls,
        andvariBaseUrls,
        transport,
        tls,
        inventory,
        adminApi,
        workDir);
  }

  private static TransportProtocol parseTransport(final Map<?, ?> root) {
    return YamlParsing.optionalString(root, "transport")
        .map(
            value ->
                switch (value.trim().toLowerCase(Locale.ROOT)) {
                  case "plaintext" -> TransportProtocol.PLAINTEXT;
                  case "mtls" -> TransportProtocol.TLS;
                  default ->
                      throw new RagnarokException(
                          "'transport' must be 'plaintext' or 'mtls', got: " + value);
                })
        .orElse(TransportProtocol.PLAINTEXT);
  }

  private static Optional<TlsSettings> parseTls(final Map<?, ?> root) {
    final Object value = root.get("tls");
    if (value == null) {
      return Optional.empty();
    }
    if (!(value instanceof Map<?, ?> tls)) {
      throw new RagnarokException("'tls' must be a mapping");
    }
    return Optional.of(
        new TlsSettings(
            Path.of(YamlParsing.requireString(tls, "certFile")),
            Path.of(YamlParsing.requireString(tls, "keyFile")),
            Path.of(YamlParsing.requireString(tls, "caFile"))));
  }

  private static Optional<InventorySpec> parseInventory(final Map<?, ?> root) {
    final Object value = root.get("inventory");
    if (value == null) {
      return Optional.empty();
    }
    if (!(value instanceof Map<?, ?> inventory)) {
      throw new RagnarokException("'inventory' must be a mapping");
    }
    return Optional.of(InventorySpecParser.parse(inventory));
  }

  private static Optional<AdminApiSpec> parseAdminApi(final Map<?, ?> root) {
    final Object value = root.get("adminApi");
    if (value == null) {
      return Optional.empty();
    }
    if (!(value instanceof Map<?, ?> adminApi)) {
      throw new RagnarokException("'adminApi' must be a mapping");
    }
    return Optional.of(AdminApiSpecParser.parse(adminApi));
  }

  private static Path defaultWorkDir() {
    return Path.of(
        System.getProperty("java.io.tmpdir"), "ragnarok-" + Long.toHexString(System.nanoTime()));
  }
}
