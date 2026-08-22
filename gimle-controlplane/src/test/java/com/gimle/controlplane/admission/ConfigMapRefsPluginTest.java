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
import com.gimle.mimir.manifest.DeploymentSpec;
import com.gimle.mimir.manifest.PlacementConstraints;
import com.gimle.mimir.raft.StateMutation;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
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
 * Unit-level coverage of {@link ConfigMapRefsPlugin}, backed by a real {@code gimle-mimir} store
 * via {@link InProcessStore} -- the same fixture {@link
 * com.gimle.controlplane.configmap.ConfigMapStoreTest} uses -- so seeded ConfigMaps go through the
 * real {@link ConfigMapStore} write path rather than a hand-encoded {@link ConfigEntry}.
 */
class ConfigMapRefsPluginTest {

  @TempDir(cleanup = CleanupMode.NEVER)
  Path tempDir;

  private InProcessStore inProcessStore;
  private ConfigMapStore configMaps;
  private final ConfigMapRefsPlugin plugin = new ConfigMapRefsPlugin();

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
  void empty_config_map_refs_is_allowed_without_consulting_the_store() {
    DeploymentSpec spec = deployment("no-refs", Optional.of("acme"), List.of());

    AdmissionDecision<DeploymentSpec> decision = review(spec);

    assertInstanceOf(AdmissionDecision.Allow.class, decision);
  }

  @Test
  void config_map_refs_with_no_tenant_id_is_rejected() {
    DeploymentSpec spec = deployment("no-tenant", Optional.empty(), List.of("app-config"));

    AdmissionDecision<DeploymentSpec> decision = review(spec);

    assertEquals(
        "deployment no-tenant declares configMapRefs but has no tenantId",
        assertInstanceOf(AdmissionDecision.Reject.class, decision).reason());
  }

  @Test
  void a_reference_to_an_unknown_configmap_is_rejected() {
    DeploymentSpec spec = deployment("dangling-ref", Optional.of("acme"), List.of("nope"));

    AdmissionDecision<DeploymentSpec> decision = review(spec);

    assertEquals(
        "deployment dangling-ref references unknown ConfigMap 'nope' for tenant acme",
        assertInstanceOf(AdmissionDecision.Reject.class, decision).reason());
  }

  @Test
  void a_known_configmap_with_no_collisions_is_allowed() {
    configMaps.put("acme", "app-config", Map.of("log.level", "INFO"), OptionalInt.empty());
    DeploymentSpec spec = deployment("clean", Optional.of("acme"), List.of("app-config"));

    AdmissionDecision<DeploymentSpec> decision = review(spec);

    assertInstanceOf(AdmissionDecision.Allow.class, decision);
  }

  @Test
  void two_referenced_configmaps_declaring_the_same_key_are_rejected() {
    configMaps.put("acme", "a", Map.of("shared", "1"), OptionalInt.empty());
    configMaps.put("acme", "b", Map.of("shared", "2"), OptionalInt.empty());
    DeploymentSpec spec = deployment("colliding-refs", Optional.of("acme"), List.of("a", "b"));

    AdmissionDecision<DeploymentSpec> decision = review(spec);

    assertEquals(
        "deployment colliding-refs: key 'shared' is declared by both ConfigMap 'a' and 'b'",
        assertInstanceOf(AdmissionDecision.Reject.class, decision).reason());
  }

  @Test
  void a_referenced_configmap_key_colliding_with_flat_config_is_rejected() {
    inProcessStore
        .client()
        .propose(
            new StateMutation.PutConfigEntry(
                new ConfigEntry(
                    "acme", "shared", "flat-value".getBytes(StandardCharsets.UTF_8), false)));
    configMaps.put("acme", "app-config", Map.of("shared", "1"), OptionalInt.empty());
    DeploymentSpec spec = deployment("flat-collision", Optional.of("acme"), List.of("app-config"));

    AdmissionDecision<DeploymentSpec> decision = review(spec);

    assertEquals(
        "deployment flat-collision: key 'shared' is declared by ConfigMap 'app-config' and also"
            + " exists as flat config for tenant acme",
        assertInstanceOf(AdmissionDecision.Reject.class, decision).reason());
  }

  private AdmissionDecision<DeploymentSpec> review(DeploymentSpec spec) {
    return plugin.review(
        new AdmissionRequest<>(
            ResourceKind.DEPLOYMENT, Verb.WRITE, spec, inProcessStore.client(), Optional.empty()));
  }

  private DeploymentSpec deployment(
      String name, Optional<String> tenantId, List<String> configMapRefs) {
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
        configMapRefs);
  }
}
