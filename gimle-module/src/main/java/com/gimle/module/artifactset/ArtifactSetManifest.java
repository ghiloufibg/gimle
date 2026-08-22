package com.gimle.module.artifactset;

import java.util.List;

/**
 * The parsed, already-flattened form of an {@code ArtifactSet} manifest -- see {@link
 * ArtifactSetManifestParser} for how the YAML's {@code tenant:}/{@code modules:} grouping collapses
 * into this single ordered list. Push order (relevant to a pre-flight/sequential-push caller) is
 * this list's own order: every {@code tenant:} entry's members in the manifest's own key and list
 * order, followed by the untenanted {@code modules:} list.
 */
public record ArtifactSetManifest(List<ArtifactSetModuleEntry> modules) {}
