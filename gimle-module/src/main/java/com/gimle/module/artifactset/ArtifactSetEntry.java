package com.gimle.module.artifactset;

import com.gimle.core.vessel.VesselEntrypoint;
import java.nio.file.Path;
import java.util.Optional;

/**
 * One flattened member of an {@code ArtifactSet} manifest. A bare-string list item is a {@link
 * Module} -- a real JPMS module jar whose coordinate is read from its own bundled {@code
 * gimle-module.yaml}, exactly the shape every entry had before mapping-form entries existed. A
 * mapping-form item names its own coordinate explicitly (neither shape below has a descriptor to
 * read one from) and is either a {@link Vessel} -- a plain runnable jar -- or a {@link Bundle} -- a
 * whole application directory zipped with its {@link VesselEntrypoint} launch descriptor at the
 * archive root.
 *
 * <p>{@code artifact} is already resolved against the manifest file's own directory -- never a raw,
 * possibly-relative string a caller would have to resolve again.
 */
public sealed interface ArtifactSetEntry {

  Path artifact();

  Optional<String> tenantId();

  record Module(Path artifact, Optional<String> tenantId) implements ArtifactSetEntry {}

  record Vessel(Path artifact, Optional<String> tenantId, String name, String version)
      implements ArtifactSetEntry {}

  record Bundle(
      Path artifact,
      Optional<String> tenantId,
      String name,
      String version,
      VesselEntrypoint entrypoint)
      implements ArtifactSetEntry {}
}
