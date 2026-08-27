package com.gimle.ragnarok.target.adminapi;

import com.gimle.ragnarok.config.YamlParsing;
import java.util.LinkedHashMap;
import java.util.Map;

/** Parses the {@code adminApi:} block of a target document into an {@link AdminApiSpec}. */
public final class AdminApiSpecParser {

  private AdminApiSpecParser() {}

  public static AdminApiSpec parse(final Map<?, ?> root) {
    final Map<String, String> endpoints = new LinkedHashMap<>();
    for (final Map<?, ?> node : YamlParsing.mapList(root, "nodes")) {
      final String nodeId = YamlParsing.requireString(node, "nodeId");
      final String endpoint = YamlParsing.requireString(node, "endpoint");
      endpoints.put(nodeId, endpoint);
    }
    return new AdminApiSpec(endpoints);
  }
}
