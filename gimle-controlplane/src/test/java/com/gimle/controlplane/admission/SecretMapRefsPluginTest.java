package com.gimle.controlplane.admission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import com.gimle.controlplane.configmap.ConfigMapStore;
import com.gimle.controlplane.testsupport.InProcessStore;
import com.gimle.core.authz.ResourceKind;
import com.gimle.core.authz.Verb;
import com.gimle.core.config.ConfigEntry;
import com.gimle.core.module.ModuleId;
import com.gimle.core.module.Version;
import com.gimle.core.protocol.Json;
import com.gimle.mimir.manifest.DeploymentSpec;
import com.gimle.mimir.manifest.PlacementConstraints;
import com.gimle.mimir.raft.StateMutation;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit-level coverage of {@link SecretMapRefsPlugin}, mirroring {@link ConfigMapRefsPluginTest}'s
 * exact shape. Since this module has no compile dependency on {@code gimle-fafnir}, SecretMap
 * member keys are seeded directly as {@code secretmap:{name}:{key}@meta} {@link ConfigEntry} rows
 * -- the identical plain-JSON envelope {@code com.gimle.fafnir.SecretStore} itself would produce --
 * rather than going through a real {@code SecretMapStore}.
 */
class SecretMapRefsPluginTest {

  @TempDir(cleanup = CleanupMode.NEVER)
  Path tempDir;

  private InProcessStore inProcessStore;
  private ConfigMapStore configMaps;
  private final SecretMapRefsPlugin plugin = new SecretMapRefsPlugin();

  @BeforeEach
  void startStore() throws IOException {
    inProcessStore = InProcessStore.start(tempDir.resolve("store"));
    configMaps = new ConfigMapStore(inProcessStore.client());
  }

  @AfterEach
  void stopStore() {
    inProcessStore.close();
  }

  @Test
  void empty_secret_map_refs_is_allowed_without_consulting_the_store() {
    DeploymentSpec spec = deployment("no-refs", Optional.of("acme"), List.of());

    AdmissionDecision<DeploymentSpec> decision = review(spec);

    assertInstanceOf(AdmissionDecision.Allow.class, decision);
  }

  @Test
  void secret_map_refs_with_no_tenant_id_is_rejected() {
    DeploymentSpec spec = deployment("no-tenant", Optional.empty(), List.of("db-creds"));

    AdmissionDecision<DeploymentSpec> decision = review(spec);

    assertEquals(
        "deployment no-tenant declares secretMapRefs but has no tenantId",
        assertInstanceOf(AdmissionDecision.Reject.class, decision).reason());
  }

  @Test
  void a_reference_to_an_unknown_secretmap_is_rejected() {
    DeploymentSpec spec = deployment("dangling-ref", Optional.of("acme"), List.of("nope"));

    AdmissionDecision<DeploymentSpec> decision = review(spec);

    assertEquals(
        "deployment dangling-ref references unknown SecretMap 'nope' for tenant acme",
        assertInstanceOf(AdmissionDecision.Reject.class, decision).reason());
  }

  @Test
  void a_known_secretmap_with_no_collisions_is_allowed() {
    seedSecretMapKey("acme", "db-creds", "username");
    DeploymentSpec spec = deployment("clean", Optional.of("acme"), List.of("db-creds"));

    AdmissionDecision<DeploymentSpec> decision = review(spec);

    assertInstanceOf(AdmissionDecision.Allow.class, decision);
  }

  @Test
  void two_referenced_secretmaps_declaring_the_same_key_are_rejected() {
    seedSecretMapKey("acme", "a", "shared");
    seedSecretMapKey("acme", "b", "shared");
    DeploymentSpec spec = deployment("colliding-refs", Optional.of("acme"), List.of("a", "b"));

    AdmissionDecision<DeploymentSpec> decision = review(spec);

    assertEquals(
        "deployment colliding-refs: key 'shared' is declared by both SecretMap 'a' and SecretMap"
            + " 'b'",
        assertInstanceOf(AdmissionDecision.Reject.class, decision).reason());
  }

  @Test
  void a_secretmap_key_colliding_with_a_configmap_key_is_rejected() {
    seedSecretMapKey("acme", "db-creds", "shared");
    configMaps.put("acme", "app-config", Map.of("shared", "1"), OptionalInt.empty());
    DeploymentSpec spec =
        deployment(
            "cross-kind-collision",
            Optional.of("acme"),
            List.of("db-creds"),
            List.of("app-config"));

    AdmissionDecision<DeploymentSpec> decision = review(spec);

    assertEquals(
        "deployment cross-kind-collision: key 'shared' is declared by SecretMap 'db-creds' and"
            + " also by ConfigMap 'app-config'",
        assertInstanceOf(AdmissionDecision.Reject.class, decision).reason());
  }

  @Test
  void a_secretmap_key_colliding_with_flat_config_is_rejected() {
    seedSecretMapKey("acme", "db-creds", "shared");
    inProcessStore
        .client()
        .propose(
            new StateMutation.PutConfigEntry(
                new ConfigEntry(
                    "acme", "shared", "flat-value".getBytes(StandardCharsets.UTF_8), false)));
    DeploymentSpec spec =
        deployment("flat-config-collision", Optional.of("acme"), List.of("db-creds"));

    AdmissionDecision<DeploymentSpec> decision = review(spec);

    assertEquals(
        "deployment flat-config-collision: key 'shared' is declared by SecretMap 'db-creds' and"
            + " also exists as flat config for tenant acme",
        assertInstanceOf(AdmissionDecision.Reject.class, decision).reason());
  }

  @Test
  void a_secretmap_key_colliding_with_a_flat_secret_is_rejected() {
    seedSecretMapKey("acme", "db-creds", "shared");
    seedFlatSecretKey("acme", "shared");
    DeploymentSpec spec =
        deployment("flat-secret-collision", Optional.of("acme"), List.of("db-creds"));

    AdmissionDecision<DeploymentSpec> decision = review(spec);

    assertEquals(
        "deployment flat-secret-collision: key 'shared' is declared by SecretMap 'db-creds' and"
            + " also exists as flat secret for tenant acme",
        assertInstanceOf(AdmissionDecision.Reject.class, decision).reason());
  }

  private AdmissionDecision<DeploymentSpec> review(DeploymentSpec spec) {
    return plugin.review(
        new AdmissionRequest<>(
            ResourceKind.DEPLOYMENT, Verb.WRITE, spec, inProcessStore.client(), Optional.empty()));
  }

  /**
   * The identical plain-JSON {@code key@meta} envelope {@code SecretStore}'s own {@code Meta}
   * record produces -- {@code {"latestVersion": 1, "deleted": false}}, unencrypted.
   */
  private void seedSecretMapKey(String tenantId, String name, String key) {
    seedMetaEntry(tenantId, "secretmap:" + name + ":" + key);
  }

  private void seedFlatSecretKey(String tenantId, String key) {
    seedMetaEntry(tenantId, key);
  }

  private void seedMetaEntry(String tenantId, String rawKeyWithoutSuffix) {
    Map<String, Object> meta = new LinkedHashMap<>();
    meta.put("latestVersion", 1);
    meta.put("deleted", false);
    inProcessStore
        .client()
        .propose(
            new StateMutation.PutConfigEntry(
                new ConfigEntry(
                    tenantId,
                    rawKeyWithoutSuffix + "@meta",
                    Json.write(meta).getBytes(StandardCharsets.UTF_8),
                    false)));
  }

  private DeploymentSpec deployment(
      String name, Optional<String> tenantId, List<String> secretMapRefs) {
    return deployment(name, tenantId, secretMapRefs, List.of());
  }

  private DeploymentSpec deployment(
      String name,
      Optional<String> tenantId,
      List<String> secretMapRefs,
      List<String> configMapRefs) {
    return new DeploymentSpec(
        name,
        new ModuleId(name, Version.parse("1.0.0")),
        tempDir.resolve(name + ".jar").toAbsolutePath().toString(),
        1,
        PlacementConstraints.NONE,
        Optional.empty(),
        tenantId,
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        configMapRefs,
        secretMapRefs);
  }
}
