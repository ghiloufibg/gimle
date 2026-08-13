package com.gimle.controlplane.admission;

import com.gimle.core.authz.ResourceKind;
import com.gimle.core.authz.Verb;
import com.gimle.core.module.ModuleArtifact;
import com.gimle.mimir.store.StoreReader;
import java.util.Optional;

/**
 * What an {@link AdmissionPlugin} needs to decide: which resource kind/verb is being admitted
 * (reusing {@link ResourceKind}/{@link Verb} rather than inventing a parallel taxonomy already used
 * for RBAC), the proposed spec, a {@link StoreReader} for current-state lookups -- the same
 * "re-derive from the store, don't cache" posture {@code Authorizer} already takes -- and the
 * spec's already-resolved module artifact, if any, so a plugin never has to re-read a jar from disk
 * that {@code ApiServer} already read once at the start of admission.
 */
public record AdmissionRequest<T>(
    ResourceKind kind, Verb verb, T spec, StoreReader store, Optional<ModuleArtifact> artifact) {}
