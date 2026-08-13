package com.gimle.controlplane.admission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.core.authz.ResourceKind;
import com.gimle.core.authz.Verb;
import com.gimle.mimir.store.StateStore;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;

/**
 * Chain-mechanics tests only -- plugin type is plain {@code String} throughout, since ordering,
 * short-circuiting, and spec mutation pass-through don't depend on any particular resource kind.
 * {@link TenantQuotaPluginTest} covers the one real plugin's own business logic.
 */
class AdmissionChainTest {

  @TempDir(cleanup = CleanupMode.ON_SUCCESS)
  Path tempDir;

  @Test
  void empty_chain_allows_the_spec_unchanged() {
    AdmissionChain<String> chain = new AdmissionChain<>(List.of());

    AdmissionDecision<String> decision =
        chain.admit(ResourceKind.DEPLOYMENT, Verb.WRITE, "original", store(), Optional.empty());

    assertTrue(decision instanceof AdmissionDecision.Allow<String>);
    assertEquals("original", ((AdmissionDecision.Allow<String>) decision).spec());
  }

  @Test
  void a_rejecting_plugin_short_circuits_every_later_plugin() {
    AtomicBoolean secondPluginRan = new AtomicBoolean(false);
    AdmissionPlugin<String> rejecting = request -> AdmissionDecision.reject("no");
    AdmissionPlugin<String> shouldNeverRun =
        request -> {
          secondPluginRan.set(true);
          return AdmissionDecision.allow(request.spec());
        };
    AdmissionChain<String> chain = new AdmissionChain<>(List.of(rejecting, shouldNeverRun));

    AdmissionDecision<String> decision =
        chain.admit(ResourceKind.DEPLOYMENT, Verb.WRITE, "original", store(), Optional.empty());

    assertTrue(decision instanceof AdmissionDecision.Reject<String>);
    assertEquals("no", ((AdmissionDecision.Reject<String>) decision).reason());
    assertFalse(secondPluginRan.get(), "a later plugin must not run once an earlier one rejects");
  }

  @Test
  void a_later_plugin_sees_the_spec_an_earlier_plugin_mutated() {
    AdmissionPlugin<String> uppercases =
        request -> AdmissionDecision.allow(request.spec().toUpperCase());
    AdmissionPlugin<String> appendsSuffix =
        request -> AdmissionDecision.allow(request.spec() + "-reviewed");
    AdmissionChain<String> chain = new AdmissionChain<>(List.of(uppercases, appendsSuffix));

    AdmissionDecision<String> decision =
        chain.admit(ResourceKind.DEPLOYMENT, Verb.WRITE, "original", store(), Optional.empty());

    assertEquals("ORIGINAL-reviewed", ((AdmissionDecision.Allow<String>) decision).spec());
  }

  private StateStore store() {
    return new StateStore(tempDir.resolve("admission-chain-store"));
  }
}
