package com.gimle.andvari;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.gimle.andvari.MavenCoordinates.ArtifactFile;
import com.gimle.andvari.MavenCoordinates.MetadataFile;
import org.junit.jupiter.api.Test;

/**
 * The GAV-path-to-module-coordinate translation in isolation from any HTTP surface -- the
 * canonicalization rule the design calls out as needing to be "stated, tested, and never left
 * implicit".
 */
class MavenCoordinatesTest {

  @Test
  void a_multi_segment_group_joins_with_the_artifact_id_by_dots() {
    ArtifactFile file =
        MavenCoordinates.parseArtifactFile(
            "com/gimle/examples/greeter/provider/1.0.0/provider-1.0.0.jar");

    assertEquals("com.gimle.examples.greeter.provider", file.moduleId());
    assertEquals("provider", file.artifactId());
    assertEquals("1.0.0", file.version());
    assertEquals("provider-1.0.0.jar", file.fileName());
  }

  @Test
  void a_single_segment_module_has_an_empty_group() {
    ArtifactFile file = MavenCoordinates.parseArtifactFile("hello/1.0.0/hello-1.0.0.jar");

    assertEquals("hello", file.moduleId());
    assertEquals("hello", file.artifactId());
  }

  @Test
  void a_sidecar_file_name_is_returned_verbatim_for_the_caller_to_classify() {
    ArtifactFile file =
        MavenCoordinates.parseArtifactFile("com/gimle/app/1.0.0/app-1.0.0.jar.sha256");

    assertEquals("app-1.0.0.jar.sha256", file.fileName());
  }

  @Test
  void too_few_segments_is_not_an_artifact_file() {
    assertNull(MavenCoordinates.parseArtifactFile("app/1.0.0"));
    assertNull(MavenCoordinates.parseArtifactFile("app"));
  }

  @Test
  void distinct_gavs_can_alias_to_the_same_module_coordinate() {
    // groupId "com.gimle.app" artifactId "sub" ...
    ArtifactFile first =
        MavenCoordinates.parseArtifactFile("com/gimle/app/sub/1.0.0/sub-1.0.0.jar");
    // ...and groupId "com.gimle" artifactId "app.sub" both resolve to module "com.gimle.app.sub".
    ArtifactFile second =
        MavenCoordinates.parseArtifactFile("com/gimle/app.sub/1.0.0/app.sub-1.0.0.jar");

    assertEquals("com.gimle.app.sub", first.moduleId());
    assertEquals("com.gimle.app.sub", second.moduleId());
  }

  @Test
  void a_group_level_metadata_path_resolves_the_module_and_gav_parts() {
    MetadataFile metadata =
        MavenCoordinates.parseMetadataPath(
            "com/gimle/examples/greeter/provider/maven-metadata.xml");

    assertEquals("com.gimle.examples.greeter.provider", metadata.moduleId());
    assertEquals("com.gimle.examples.greeter", metadata.groupId());
    assertEquals("provider", metadata.artifactId());
    assertEquals("maven-metadata.xml", metadata.fileName());
  }

  @Test
  void a_metadata_checksum_sidecar_keeps_the_full_file_name() {
    MetadataFile metadata = MavenCoordinates.parseMetadataPath("hello/maven-metadata.xml.sha256");

    assertEquals("hello", metadata.moduleId());
    assertEquals("", metadata.groupId());
    assertEquals("maven-metadata.xml.sha256", metadata.fileName());
  }

  @Test
  void a_non_metadata_last_segment_is_not_a_metadata_path() {
    assertNull(MavenCoordinates.parseMetadataPath("com/gimle/app/1.0.0/app-1.0.0.jar"));
    assertNull(MavenCoordinates.parseMetadataPath("onlyonesegment"));
  }
}
