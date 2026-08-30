package com.gimle.holmgang.steps;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import com.gimle.core.config.ConfigEntry;
import com.gimle.core.protocol.Json;
import com.gimle.fafnir.secret.KeyFileManager;
import com.gimle.holmgang.HolmgangException;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Steps exercising Fafnir's own versioned secret store, key rotation, node-scoped defense-in-depth
 * authorization, and its own operator console session -- everything reachable through the real
 * {@code gimle secret *} CLI/API surface plus Fafnir's own direct port, distinct from the
 * plain-tenant-secret provisioning step in {@link TenantSteps}.
 */
public final class FafnirSteps {

  private static final int GCM_IV_LENGTH_BYTES = 12;
  private static final int GCM_TAG_LENGTH_BITS = 128;
  private static final SecureRandom RANDOM = new SecureRandom();
  // Fafnir's own reads are ordinary StoreClient round-robin reads (no leader-awareness -- see
  // StoreRpc's own javadoc), so a read immediately following a write can transiently land on a
  // replica that has not yet replicated it. Every assertion below that follows a write polls
  // through Heimdall rather than checking once, the same convergence-tolerant idiom every other
  // store-backed condition in this suite already uses (ClusterApi.isDeploymentActive and friends).
  private static final Duration CONVERGENCE_TIMEOUT = Duration.ofSeconds(30);

  private final ScenarioWorld world;

  public FafnirSteps(final ScenarioWorld world) {
    this.world = world;
  }

  // ---- versioning, soft/hard delete ----

  @Then("secret {string} for tenant {string} has versions {string}")
  public void secretHasVersions(final String key, final String tenantId, final String csv) {
    final List<Integer> expected = Arrays.stream(csv.split(",")).map(Integer::parseInt).toList();
    awaitCondition(
        "secret " + key + " has versions " + csv,
        () -> expected.equals(world.cluster().api().secretVersions(tenantId, key)));
  }

  @Then("the latest value of secret {string} for tenant {string} is {string}")
  public void theLatestValueOfSecretIs(
      final String key, final String tenantId, final String expected) {
    awaitCondition(
        "latest value of secret " + key + " is " + expected,
        () ->
            Optional.of(expected)
                .equals(world.cluster().api().getSecret(tenantId, key, Optional.empty())));
  }

  @Then("the latest value of secret {string} for tenant {string} is absent")
  public void theLatestValueOfSecretIsAbsent(final String key, final String tenantId) {
    awaitCondition(
        "latest value of secret " + key + " is absent",
        () -> world.cluster().api().getSecret(tenantId, key, Optional.empty()).isEmpty());
  }

  @Then("version {int} of secret {string} for tenant {string} is {string}")
  public void versionOfSecretIs(
      final int version, final String key, final String tenantId, final String expected) {
    awaitCondition(
        "version " + version + " of secret " + key + " is " + expected,
        () ->
            Optional.of(expected)
                .equals(world.cluster().api().getSecret(tenantId, key, Optional.of(version))));
  }

  @Then("version {int} of secret {string} for tenant {string} is absent")
  public void versionOfSecretIsAbsent(final int version, final String key, final String tenantId) {
    awaitCondition(
        "version " + version + " of secret " + key + " is absent",
        () -> world.cluster().api().getSecret(tenantId, key, Optional.of(version)).isEmpty());
  }

  @When("secret {string} for tenant {string} is soft deleted")
  public void secretIsSoftDeleted(final String key, final String tenantId) {
    world.cluster().api().softDeleteSecret(tenantId, key);
  }

  @When("secret {string} for tenant {string} is hard deleted")
  public void secretIsHardDeleted(final String key, final String tenantId) {
    world.cluster().api().hardDeleteSecret(tenantId, key);
  }

  // ---- key rotation ----

  @When("the secrets key is rotated")
  public void theSecretsKeyIsRotated() {
    world.rotatedSecretsKeyIds.add(world.cluster().api().rotateSecretsKey());
  }

  @Then("the last two secrets key rotations produced different active key ids")
  public void theLastTwoSecretsKeyRotationsProducedDifferentActiveKeyIds() {
    final int count = world.rotatedSecretsKeyIds.size();
    if (count < 2) {
      throw new HolmgangException(
          "fewer than two rotations were recorded in this scenario: " + world.rotatedSecretsKeyIds);
    }
    assertNotEquals(
        world.rotatedSecretsKeyIds.get(count - 2), world.rotatedSecretsKeyIds.get(count - 1));
  }

  @Then("secret {string} for tenant {string} is stored under key id {int}")
  public void secretIsStoredUnderKeyId(final String key, final String tenantId, final int keyId) {
    awaitCondition(
        "secret " + key + " is stored under key id " + keyId,
        () -> storedKeyId(tenantId, key).map(id -> id == (byte) keyId).orElse(false));
  }

  @Then("secret {string} for tenant {string} is now stored under the newly rotated key id")
  public void secretIsNowStoredUnderTheNewlyRotatedKeyId(final String key, final String tenantId) {
    if (world.rotatedSecretsKeyIds.isEmpty()) {
      throw new HolmgangException("no rotation was recorded in this scenario");
    }
    final int mostRecent = world.rotatedSecretsKeyIds.get(world.rotatedSecretsKeyIds.size() - 1);
    awaitCondition(
        "secret " + key + " is stored under the newly rotated key id " + mostRecent,
        () -> storedKeyId(tenantId, key).map(id -> id == (byte) mostRecent).orElse(false));
  }

  /**
   * The key id byte (offset 1, per {@code SecretCipher}'s own {@code version||keyId||iv||...}
   * layout) of a secret's own latest-version raw ciphertext -- read straight from the store, never
   * through Fafnir's decrypting read API, since that API only ever returns plaintext. Empty (never
   * throws) when the store has no versions to inspect yet -- a legitimate transient state a caller
   * polls through, not a hard failure.
   */
  private Optional<Byte> storedKeyId(final String tenantId, final String key) {
    final Optional<Integer> latestVersion =
        world.cluster().api().secretVersions(tenantId, key).stream().max(Integer::compareTo);
    if (latestVersion.isEmpty()) {
      return Optional.empty();
    }
    final String rawKey = key + "@" + latestVersion.get();
    for (final ConfigEntry entry : world.cluster().configEntriesFor(tenantId)) {
      if (entry.key().equals(rawKey)) {
        return Optional.of(entry.value()[1]);
      }
    }
    return Optional.empty();
  }

  private void awaitCondition(final String description, final BooleanSupplier condition) {
    world.cluster().when().probe(description, condition).await(CONVERGENCE_TIMEOUT);
  }

  // ---- legacy pre-key-id ciphertext ----

  /**
   * Plants a secret directly in the store, encrypted with the exact legacy layout ({@code iv(12) ||
   * ciphertext-with-tag}, no version/key-id prefix) under Fafnir's own real key id 0 -- the one
   * format no current write path produces anymore, proving {@code SecretCipher.decrypt}'s own
   * fallback still reads it.
   */
  @Given("a legacy-format secret {string} = {string} is planted for tenant {string}")
  public void aLegacyFormatSecretIsPlanted(
      final String key, final String value, final String tenantId) {
    final SecretKey activeKey = KeyFileManager.loadOrCreate(world.cluster().fafnirKeyFilePath());
    final byte[] legacyCiphertext =
        legacyEncrypt(value.getBytes(StandardCharsets.UTF_8), activeKey);
    world
        .cluster()
        .proposeConfigEntry(new ConfigEntry(tenantId, key + "@1", legacyCiphertext, true));
    final Map<String, Object> meta = new LinkedHashMap<>();
    meta.put("latestVersion", 1);
    meta.put("highestVersion", 1);
    meta.put("deleted", false);
    world
        .cluster()
        .proposeConfigEntry(
            new ConfigEntry(
                tenantId, key + "@meta", Json.write(meta).getBytes(StandardCharsets.UTF_8), false));
  }

  private static byte[] legacyEncrypt(final byte[] plaintext, final SecretKey key) {
    final byte[] iv = new byte[GCM_IV_LENGTH_BYTES];
    RANDOM.nextBytes(iv);
    try {
      final Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));
      final byte[] ciphertext = cipher.doFinal(plaintext);
      final byte[] result = new byte[iv.length + ciphertext.length];
      System.arraycopy(iv, 0, result, 0, iv.length);
      System.arraycopy(ciphertext, 0, result, iv.length, ciphertext.length);
      return result;
    } catch (final GeneralSecurityException e) {
      throw new HolmgangException("legacy-format test encryption failed", e);
    }
  }

  // ---- node-tenant-scoped direct reads (bypassing the control-plane proxy entirely) ----

  @Then(
      "node {string} reading secret {string} for tenant {string} directly from Fafnir succeeds"
          + " with value {string}")
  public void nodeReadingSecretDirectlyFromFafnirSucceeds(
      final String nodeId, final String key, final String tenantId, final String expectedValue) {
    awaitCondition(
        "node " + nodeId + " reads secret " + key + " as " + expectedValue + " from Fafnir",
        () -> {
          final HttpResponse<String> response = readSecretAsNode(nodeId, tenantId, key);
          if (response.statusCode() != 200) {
            return false;
          }
          final Map<String, Object> body = Json.asObject(Json.parse(response.body()));
          final byte[] decoded = Base64.getDecoder().decode((String) body.get("value"));
          return expectedValue.equals(new String(decoded, StandardCharsets.UTF_8));
        });
  }

  @Then("node {string} reading secret {string} for tenant {string} directly from Fafnir is denied")
  public void nodeReadingSecretDirectlyFromFafnirIsDenied(
      final String nodeId, final String key, final String tenantId) {
    final HttpResponse<String> response = readSecretAsNode(nodeId, tenantId, key);
    assertEquals(403, response.statusCode(), response.body());
  }

  private HttpResponse<String> readSecretAsNode(
      final String nodeId, final String tenantId, final String key) {
    try {
      return world
          .cluster()
          .nodeIdentityHttpClient(nodeId)
          .send(
              HttpRequest.newBuilder(
                      URI.create(
                          world.cluster().fafnirBaseUrl(0) + "/secrets/" + tenantId + "/" + key))
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    } catch (final Exception e) {
      throw new HolmgangException("direct Fafnir read as node " + nodeId + " failed", e);
    }
  }

  // ---- Fafnir's own console session ----

  private static final HttpClient CONSOLE_CLIENT = HttpClient.newHttpClient();

  /**
   * Logs in and, on success, carves the raw {@code name=value} pair out of the response's own
   * {@code Set-Cookie} header into {@link ScenarioWorld#fafnirSessionCookie} for later requests to
   * reattach by hand -- see that field's own javadoc for why this is manual rather than delegated
   * to {@code HttpClient}'s built-in {@code CookieManager}.
   */
  @When("an operator logs in to the Fafnir console as {string} with password {string}")
  public void anOperatorLogsInToTheFafnirConsole(final String username, final String password) {
    final Map<String, Object> body = new LinkedHashMap<>();
    body.put("username", username);
    body.put("password", password);
    final HttpResponse<String> response = postFafnir("/auth/login", Json.write(body));
    world.lastFafnirAuthStatus = response.statusCode();
    final String setCookie = response.headers().firstValue("Set-Cookie").orElse("");
    if (!setCookie.isBlank()) {
      world.fafnirSessionCookie = setCookie.substring(0, setCookie.indexOf(';'));
    }
  }

  @Then("the Fafnir console login succeeds")
  public void theFafnirConsoleLoginSucceeds() {
    assertEquals(200, world.lastFafnirAuthStatus);
  }

  @Then("the Fafnir console session reports {string} as logged in")
  public void theFafnirConsoleSessionReportsAsLoggedIn(final String username) {
    final Map<String, Object> session = fafnirSession();
    assertEquals(username, session.get("username"));
    assertEquals(Boolean.FALSE, session.get("anonymous"));
  }

  @Then("the Fafnir console session is anonymous")
  public void theFafnirConsoleSessionIsAnonymous() {
    assertEquals(Boolean.TRUE, fafnirSession().get("anonymous"));
  }

  @When("the operator logs out of the Fafnir console")
  public void theOperatorLogsOutOfTheFafnirConsole() {
    final int status = postFafnir("/auth/logout", "").statusCode();
    assertEquals(200, status);
    world.fafnirSessionCookie = null;
  }

  private Map<String, Object> fafnirSession() {
    try {
      final HttpRequest.Builder request =
          HttpRequest.newBuilder(URI.create(world.cluster().fafnirBaseUrl(0) + "/auth/session"))
              .GET();
      if (world.fafnirSessionCookie != null) {
        request.header("Cookie", world.fafnirSessionCookie);
      }
      final HttpResponse<String> response =
          CONSOLE_CLIENT.send(
              request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      assertEquals(200, response.statusCode(), response.body());
      return Json.asObject(Json.parse(response.body()));
    } catch (final Exception e) {
      throw new HolmgangException("Fafnir console session read failed", e);
    }
  }

  private HttpResponse<String> postFafnir(final String path, final String body) {
    try {
      final HttpRequest.Builder request =
          HttpRequest.newBuilder(URI.create(world.cluster().fafnirBaseUrl(0) + path))
              .header("Content-Type", "application/json")
              .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
      if (world.fafnirSessionCookie != null) {
        request.header("Cookie", world.fafnirSessionCookie);
      }
      return CONSOLE_CLIENT.send(
          request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    } catch (final Exception e) {
      throw new HolmgangException("Fafnir console request to " + path + " failed", e);
    }
  }
}
