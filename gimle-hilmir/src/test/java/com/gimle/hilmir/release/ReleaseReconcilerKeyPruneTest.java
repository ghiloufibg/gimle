package com.gimle.hilmir.release;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Regression coverage for {@link ReleaseReconciler#computeKeyPrune}'s kind-blindness bug: a new
 * revision replacing an old SECRET with a CONFIG entry of the identical (tenant, key) must not read
 * as "still present" for the old secret's own pruning purposes, and vice versa.
 */
class ReleaseReconcilerKeyPruneTest {

  private static ReleaseRevision revisionOf(
      List<RenderedConfigEntry> config, List<SecretRef> secrets) {
    return new ReleaseRevision(
        1,
        0L,
        List.of(),
        config,
        secrets,
        List.of(),
        Optional.empty(),
        ReleaseRevisionStatus.SUCCEEDED);
  }

  private static RenderedBundle bundleOf(
      List<RenderedConfigEntry> config, List<RenderedSecretEntry> secrets) {
    return new RenderedBundle("suite", "1.0.0", List.of(), config, secrets, List.of());
  }

  @Test
  void a_secret_replaced_by_a_same_named_config_entry_is_still_flagged_for_pruning() {
    ReleaseRevision previous =
        revisionOf(List.of(), List.of(new SecretRef("acme", "db-password", "digest1")));
    RenderedBundle rendered =
        bundleOf(
            List.of(new RenderedConfigEntry("acme", "db-password", "new-plaintext-value")),
            List.of());

    List<KeyRef> stale = ReleaseReconciler.computeKeyPrune(rendered, previous);

    assertTrue(
        stale.contains(new KeyRef("acme", "db-password")),
        "the old secret must be pruned even though a config entry now reuses its key name");
  }

  @Test
  void a_config_entry_replaced_by_a_same_named_secret_is_still_flagged_for_pruning() {
    ReleaseRevision previous =
        revisionOf(List.of(new RenderedConfigEntry("acme", "greeting", "hello")), List.of());
    RenderedBundle rendered =
        bundleOf(List.of(), List.of(new RenderedSecretEntry("acme", "greeting", "shh")));

    List<KeyRef> stale = ReleaseReconciler.computeKeyPrune(rendered, previous);

    assertTrue(
        stale.contains(new KeyRef("acme", "greeting")),
        "the old config entry must be pruned even though a secret now reuses its key name");
  }

  @Test
  void a_dropped_key_with_a_different_name_is_still_correctly_pruned() {
    ReleaseRevision previous =
        revisionOf(List.of(), List.of(new SecretRef("acme", "db-password", "digest1")));
    RenderedBundle rendered =
        bundleOf(List.of(new RenderedConfigEntry("acme", "unrelated-key", "value")), List.of());

    List<KeyRef> stale = ReleaseReconciler.computeKeyPrune(rendered, previous);

    assertTrue(stale.contains(new KeyRef("acme", "db-password")));
  }

  @Test
  void a_secret_kept_as_a_secret_under_the_same_key_is_not_pruned() {
    ReleaseRevision previous =
        revisionOf(List.of(), List.of(new SecretRef("acme", "db-password", "digest1")));
    RenderedBundle rendered =
        bundleOf(List.of(), List.of(new RenderedSecretEntry("acme", "db-password", "unchanged")));

    List<KeyRef> stale = ReleaseReconciler.computeKeyPrune(rendered, previous);

    assertFalse(stale.contains(new KeyRef("acme", "db-password")));
  }

  @Test
  void a_config_entry_kept_as_config_under_the_same_key_is_not_pruned() {
    ReleaseRevision previous =
        revisionOf(List.of(new RenderedConfigEntry("acme", "greeting", "hello")), List.of());
    RenderedBundle rendered =
        bundleOf(List.of(new RenderedConfigEntry("acme", "greeting", "hello again")), List.of());

    List<KeyRef> stale = ReleaseReconciler.computeKeyPrune(rendered, previous);

    assertFalse(stale.contains(new KeyRef("acme", "greeting")));
  }
}
