package com.gimle.ivaldi.validate;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * One entry of a rendered file set's {@code ivaldi.artifacts.yaml}: the local jar backing a
 * manifest whose module coordinate is otherwise unresolvable, plus the manifest it backs.
 *
 * <p>The rendered manifests deliberately carry no {@code artifactPath} of their own. The platform
 * deprecates that field -- it is resolved against the reading process's own working directory, so
 * it cannot survive being applied from anywhere but the machine that wrote it -- and both ways a
 * file set is used push the jar to the artifact registry first, which is what a module coordinate
 * with no path resolves through. Recording the jar here keeps that bookkeeping in a document Ivaldi
 * owns rather than smuggling it through a manifest the cluster also reads.
 */
public record JarArtifact(String manifestPath, Path jar) {

  private static final String SIDECAR_PATH = "ivaldi.artifacts.yaml";

  /** The jar-backed workloads of {@code files}, empty when the set declares none. */
  public static List<JarArtifact> readFrom(List<RenderedFile> files) {
    List<JarArtifact> artifacts = new ArrayList<>();
    for (RenderedFile file : files) {
      if (!SIDECAR_PATH.equals(file.path())) {
        continue;
      }
      Yaml yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
      Object raw = yaml.load(file.content());
      if (!(raw instanceof Map<?, ?> root) || !(root.get("artifacts") instanceof List<?> list)) {
        throw new IllegalArgumentException(
            SIDECAR_PATH + " must contain an 'artifacts' list of {manifest, path} entries");
      }
      for (Object entry : list) {
        if (!(entry instanceof Map<?, ?> mapping)) {
          throw new IllegalArgumentException(SIDECAR_PATH + " entries must be mappings");
        }
        Object path = mapping.get("path");
        if (!(path instanceof String pathString) || pathString.isBlank()) {
          throw new IllegalArgumentException(
              SIDECAR_PATH + " entry for " + mapping.get("manifest") + " has no 'path'");
        }
        artifacts.add(
            new JarArtifact(String.valueOf(mapping.get("manifest")), Path.of(pathString)));
      }
    }
    return List.copyOf(artifacts);
  }
}
