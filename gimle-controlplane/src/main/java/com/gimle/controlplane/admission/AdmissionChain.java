package com.gimle.controlplane.admission;

import com.gimle.core.authz.ResourceKind;
import com.gimle.core.authz.Verb;
import com.gimle.core.module.ModuleArtifact;
import com.gimle.mimir.store.StoreReader;
import java.util.List;
import java.util.Optional;

/**
 * Runs an ordered list of {@link AdmissionPlugin}s against one submission, short-circuiting on the
 * first rejection. A later plugin sees whatever spec an earlier one allowed through, not
 * necessarily the original -- a plugin may hand back a mutated spec rather than merely echo the one
 * it received (see {@link AdmissionDecision.Allow}). Replaces what used to be a single hardcoded
 * check called directly from {@code ApiServer} (tenant quota) with a real extension point; this
 * class is deliberately a plain ordered list built once by its caller, not a dynamic/webhook-based
 * registry -- nothing in this codebase yet needs plugins registered at runtime rather than compiled
 * in.
 */
public final class AdmissionChain<T> {

  private final List<AdmissionPlugin<T>> plugins;

  public AdmissionChain(List<? extends AdmissionPlugin<T>> plugins) {
    this.plugins = List.copyOf(plugins);
  }

  public AdmissionDecision<T> admit(
      ResourceKind kind, Verb verb, T spec, StoreReader store, Optional<ModuleArtifact> artifact) {
    T current = spec;
    for (AdmissionPlugin<T> plugin : plugins) {
      AdmissionDecision<T> decision =
          plugin.review(new AdmissionRequest<>(kind, verb, current, store, artifact));
      switch (decision) {
        case AdmissionDecision.Reject<T> reject -> {
          return reject;
        }
        case AdmissionDecision.Allow<T> allow -> current = allow.spec();
      }
    }
    return AdmissionDecision.allow(current);
  }
}
