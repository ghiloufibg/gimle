package com.gimle.module.artifactset;

import java.nio.file.Path;
import java.util.Optional;

/**
 * One flattened member of an {@code ArtifactSet} manifest: a jar to publish, plus the tenant it
 * belongs to if the manifest named one. {@code artifact} is already resolved against the manifest
 * file's own directory -- never a raw, possibly-relative string a caller would have to resolve
 * again.
 */
public record ArtifactSetModuleEntry(Path artifact, Optional<String> tenantId) {}
