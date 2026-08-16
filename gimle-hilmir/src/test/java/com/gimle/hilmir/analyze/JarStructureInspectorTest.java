package com.gimle.hilmir.analyze;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.hilmir.analyze.testsupport.HilmirTestJarBuilder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JarStructureInspectorTest {

  @TempDir Path tempDir;

  @Test
  void classifies_a_plain_jar() throws Exception {
    Path jar =
        HilmirTestJarBuilder.create()
            .withModuleInfo(HilmirTestJarBuilder.minimalModuleInfo("com.example.plain"))
            .build(tempDir, "plain.jar");
    assertEquals(ArtifactPackaging.PLAIN_JAR, JarStructureInspector.classifyPackaging(jar));
  }

  @Test
  void classifies_a_war_by_extension() throws Exception {
    Path war = tempDir.resolve("app.war");
    Files.writeString(war, "not really a jar", StandardCharsets.UTF_8);
    assertEquals(ArtifactPackaging.WAR, JarStructureInspector.classifyPackaging(war));
  }

  @Test
  void classifies_an_ear_by_extension() throws Exception {
    Path ear = tempDir.resolve("app.ear");
    Files.writeString(ear, "not really a jar", StandardCharsets.UTF_8);
    assertEquals(ArtifactPackaging.EAR, JarStructureInspector.classifyPackaging(ear));
  }

  @Test
  void classifies_a_directory() throws Exception {
    Path dir = Files.createDirectory(tempDir.resolve("exploded"));
    assertEquals(ArtifactPackaging.DIRECTORY, JarStructureInspector.classifyPackaging(dir));
  }

  @Test
  void classifies_a_non_zip_file_as_not_a_jar() throws Exception {
    Path binary = tempDir.resolve("native-image-exe");
    Files.write(binary, new byte[] {0x7f, 'E', 'L', 'F'});
    assertEquals(ArtifactPackaging.NOT_A_JAR, JarStructureInspector.classifyPackaging(binary));
  }

  @Test
  void detects_module_info_and_descriptor_presence() throws Exception {
    Path jar =
        HilmirTestJarBuilder.create()
            .withModuleInfo(HilmirTestJarBuilder.minimalModuleInfo("com.example.mod"))
            .withDescriptor(HilmirTestJarBuilder.minimalDescriptor("com.example.mod", "1.0.0"))
            .build(tempDir, "with-descriptor.jar");
    JarStructure structure = JarStructureInspector.inspect(jar);
    assertTrue(structure.hasModuleInfo());
    assertTrue(structure.hasGimleDescriptor());
    assertFalse(structure.launcherArchiveShaped());
  }

  @Test
  void detects_a_spring_boot_style_launcher_archive() throws Exception {
    Path jar =
        HilmirTestJarBuilder.create()
            .withMainClass("org.springframework.boot.loader.JarLauncher")
            .withExtraEntry("BOOT-INF/classes/", new byte[0])
            .withExtraEntry("BOOT-INF/lib/", new byte[0])
            .withExtraEntry("BOOT-INF/lib/logback-classic-1.4.14.jar", new byte[] {1, 2, 3})
            .build(tempDir, "fat.jar");
    JarStructure structure = JarStructureInspector.inspect(jar);
    assertTrue(structure.launcherArchiveShaped());
    assertFalse(structure.hasModuleInfo());
    assertTrue(
        structure.loggingBindingHits().stream().anyMatch(h -> h.contains("logback-classic")));
  }

  @Test
  void detects_bundled_native_library_entries() throws Exception {
    Path jar =
        HilmirTestJarBuilder.create()
            .withModuleInfo(HilmirTestJarBuilder.minimalModuleInfo("com.example.native1"))
            .withExtraEntry("native/linux64/libfoo.so", new byte[] {1})
            .build(tempDir, "native.jar");
    JarStructure structure = JarStructureInspector.inspect(jar);
    assertEquals(1, structure.nativeLibraryEntries().size());
    assertTrue(structure.nativeLibraryEntries().get(0).endsWith("libfoo.so"));
  }

  @Test
  void reads_manifest_implementation_version() throws Exception {
    Path jar =
        HilmirTestJarBuilder.create()
            .withModuleInfo(HilmirTestJarBuilder.minimalModuleInfo("com.example.versioned"))
            .withImplementationVersion("2.3.4")
            .build(tempDir, "versioned.jar");
    JarStructure structure = JarStructureInspector.inspect(jar);
    assertEquals("2.3.4", structure.manifestImplementationVersion().orElseThrow());
  }
}
