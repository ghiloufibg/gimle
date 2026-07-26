package com.gimle.module.resolve;

import static com.gimle.module.resolve.TestArtifacts.artifact;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.core.exception.GimleManifestException;
import com.gimle.core.module.ModuleArtifact;
import com.gimle.core.module.ModuleId;
import com.gimle.module.lifecycle.ModuleState;
import java.util.NoSuchElementException;
import org.junit.jupiter.api.Test;

class ModuleRegistryTest {

  @Test
  void register_stores_as_installed() {
    ModuleRegistry registry = new ModuleRegistry();
    ModuleArtifact a = artifact("com.gimle.a", "1.0.0");
    ModuleId id = registry.register(a);
    assertEquals(a.id(), id);
    assertEquals(ModuleState.INSTALLED, registry.state(id));
  }

  @Test
  void reregistering_identical_artifact_is_idempotent() {
    ModuleRegistry registry = new ModuleRegistry();
    ModuleArtifact a = artifact("com.gimle.a", "1.0.0");
    registry.register(a);
    assertDoesNotThrow(() -> registry.register(a));
  }

  @Test
  void reregistering_same_id_with_different_content_is_rejected() {
    ModuleRegistry registry = new ModuleRegistry();
    ModuleArtifact a = artifact("com.gimle.a", "1.0.0");
    registry.register(a);
    ModuleArtifact differentContent =
        new ModuleArtifact(a.id(), a.jarPath(), a.descriptor(), "f".repeat(64));
    assertThrows(GimleManifestException.class, () -> registry.register(differentContent));
  }

  @Test
  void unknown_module_id_throws() {
    ModuleRegistry registry = new ModuleRegistry();
    ModuleId unknown = artifact("com.gimle.ghost", "1.0.0").id();
    assertThrows(NoSuchElementException.class, () -> registry.state(unknown));
  }

  @Test
  void named_transitions_update_state() {
    ModuleRegistry registry = new ModuleRegistry();
    ModuleArtifact a = artifact("com.gimle.a", "1.0.0");
    ModuleId id = registry.register(a);

    registry.markResolved(id, new ModuleWiring(id, java.util.Map.of()), nullHandle());
    assertEquals(ModuleState.RESOLVED, registry.state(id));

    registry.markStarting(id);
    assertEquals(ModuleState.STARTING, registry.state(id));

    registry.markActive(id);
    assertEquals(ModuleState.ACTIVE, registry.state(id));

    registry.markStopping(id);
    assertEquals(ModuleState.STOPPING, registry.state(id));

    registry.remove(id);
    assertTrue(!registry.contains(id));
    assertThrows(NoSuchElementException.class, () -> registry.state(id));
  }

  @Test
  void mark_failed_is_reachable() {
    ModuleRegistry registry = new ModuleRegistry();
    ModuleArtifact a = artifact("com.gimle.a", "1.0.0");
    ModuleId id = registry.register(a);
    registry.markFailed(id);
    assertEquals(ModuleState.FAILED, registry.state(id));
  }

  // markResolved requires a ModuleLayerHandle in production use, but these registry-level tests
  // don't need a real one — layer construction is exercised separately once that package exists.
  private static com.gimle.module.layer.ModuleLayerHandle nullHandle() {
    return new com.gimle.module.layer.ModuleLayerHandle(
        ModuleLayer.boot(), ClassLoader.getSystemClassLoader());
  }
}
