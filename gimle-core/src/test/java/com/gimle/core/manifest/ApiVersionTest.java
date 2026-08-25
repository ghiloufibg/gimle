package com.gimle.core.manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.core.exception.GimleManifestException;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ApiVersionTest {

  private static final Set<ApiVersion> BOTH = Set.of(ApiVersion.V1ALPHA1, ApiVersion.V1);

  @Test
  void an_absent_api_version_defaults_to_v1alpha1() {
    assertEquals(ApiVersion.V1ALPHA1, ApiVersion.of(Map.of(), "Deployment", BOTH));
  }

  @Test
  void a_declared_version_resolves_by_exact_token() {
    assertEquals(ApiVersion.V1, ApiVersion.of(Map.of("apiVersion", "v1"), "Deployment", BOTH));
    assertEquals(
        ApiVersion.V1ALPHA1, ApiVersion.of(Map.of("apiVersion", "v1alpha1"), "Deployment", BOTH));
  }

  @Test
  void matching_is_case_sensitive() {
    assertThrows(
        GimleManifestException.class,
        () -> ApiVersion.of(Map.of("apiVersion", "V1"), "Deployment", BOTH));
  }

  @Test
  void an_unknown_version_is_rejected_naming_the_kind_and_supported_set() {
    GimleManifestException failure =
        assertThrows(
            GimleManifestException.class,
            () -> ApiVersion.of(Map.of("apiVersion", "v2beta3"), "ArtifactSet", BOTH));
    assertTrue(failure.getMessage().contains("'v2beta3'"), failure.getMessage());
    assertTrue(failure.getMessage().contains("kind ArtifactSet"), failure.getMessage());
    assertTrue(
        failure.getMessage().contains("v1alpha1 (default when omitted), v1"), failure.getMessage());
  }

  @Test
  void a_known_version_outside_the_kinds_supported_set_is_rejected() {
    GimleManifestException failure =
        assertThrows(
            GimleManifestException.class,
            () ->
                ApiVersion.of(
                    Map.of("apiVersion", "v1"), "Deployment", Set.of(ApiVersion.V1ALPHA1)));
    assertTrue(failure.getMessage().contains("unsupported apiVersion 'v1'"), failure.getMessage());
  }

  @Test
  void a_blank_api_version_throws_rather_than_defaulting() {
    assertThrows(
        GimleManifestException.class,
        () -> ApiVersion.of(Map.of("apiVersion", "  "), "Deployment", BOTH));
  }

  @Test
  void a_non_string_api_version_throws() {
    assertThrows(
        GimleManifestException.class,
        () -> ApiVersion.of(Map.of("apiVersion", 1), "Deployment", BOTH));
  }

  @Test
  void an_explicit_null_value_defaults_like_an_absent_key() {
    // SnakeYAML parses a bare `apiVersion:` line to a null value -- treated as absent, since
    // there is no version text to disagree with the default.
    Map<String, Object> root = new HashMap<>();
    root.put("apiVersion", null);
    assertEquals(ApiVersion.V1ALPHA1, ApiVersion.of(root, "Deployment", BOTH));
  }
}
