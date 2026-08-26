package com.gimle.controlplane.authz;

import com.gimle.core.authz.Account;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Loads {@code gimle-pki}'s {@code bootstrap-account.yaml} -- the hand-off artifact {@code
 * PkiBootstrapMain} writes because it runs standalone, before any control-plane process (and
 * therefore no {@code StateStore}/Raft) exists to propose a real {@code Account} into. Read from a
 * new, control-plane-only system property, {@code gimle.bootstrap.accountFile}, the same "optional,
 * opt-in" shape {@code CaKeyMaterial} already uses for {@code gimle.tls.caKeyFile}.
 *
 * <p>Unlike {@code CaKeyMaterial}, a missing file at a configured path is not an error here: the
 * property may deliberately stay set across restarts while an operator removes the file itself once
 * the bootstrap account is no longer needed -- the caller (see {@code ApiServer}'s constructor)
 * only ever consults this once, while the store still has zero accounts, so a later restart finding
 * the file gone is the expected steady state, not a broken configuration.
 */
public final class BootstrapAccountFile {

  private static final String BOOTSTRAP_ACCOUNT_FILE_PROPERTY = "gimle.bootstrap.accountFile";

  private BootstrapAccountFile() {}

  public static Optional<Account> loadIfConfigured() {
    String property = System.getProperty(BOOTSTRAP_ACCOUNT_FILE_PROPERTY);
    if (property == null || property.isBlank()) {
      return Optional.empty();
    }
    Path file = Path.of(property);
    if (!Files.isRegularFile(file)) {
      return Optional.empty();
    }
    try {
      byte[] bytes = Files.readAllBytes(file);
      Yaml yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
      Object raw = yaml.load(new ByteArrayInputStream(bytes));
      if (!(raw instanceof Map<?, ?> map)) {
        throw new IllegalStateException("expected a YAML mapping in " + file);
      }
      String username = (String) map.get("username");
      byte[] passwordHash = Base64.getDecoder().decode((String) map.get("passwordHash"));
      return Optional.of(new Account(username, passwordHash));
    } catch (IOException e) {
      throw new UncheckedIOException("failed to read bootstrap account file: " + file, e);
    }
  }
}
