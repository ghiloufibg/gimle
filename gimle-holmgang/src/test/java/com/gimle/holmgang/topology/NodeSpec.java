package com.gimle.holmgang.topology;

import com.gimle.core.exception.GimleManifestException;
import java.util.List;

/** One node agent in the topology: its node id and the labels it advertises for placement. */
public record NodeSpec(String id, List<String> labels) {

  public NodeSpec {
    if (id == null || id.isBlank()) {
      throw new GimleManifestException("node id must be a non-blank string");
    }
    labels = List.copyOf(labels);
  }
}
