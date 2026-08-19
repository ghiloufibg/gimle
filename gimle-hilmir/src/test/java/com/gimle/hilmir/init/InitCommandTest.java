package com.gimle.hilmir.init;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.hilmir.HilmirException;
import com.gimle.hilmir.analyze.testsupport.HilmirTestJarBuilder;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class InitCommandTest {

  @TempDir Path tempDir;

  @Test
  void generates_a_module_yaml_and_deployment_yaml_with_a_detected_probe() throws Exception {
    Path outDir = Files.createDirectory(tempDir.resolve("out1"));
    Path jar =
        HilmirTestJarBuilder.create()
            .withModuleInfo(HilmirTestJarBuilder.minimalModuleInfo("com.example.initted"))
            .withClass(
                "com.gimle.module.probe.LivenessProbe",
                "package com.gimle.module.probe; public interface LivenessProbe { boolean isAlive(); }")
            .withClass(
                "com.example.initted.MyProbe",
                """
                package com.example.initted;
                import com.gimle.module.probe.LivenessProbe;
                public class MyProbe implements LivenessProbe {
                  public MyProbe() {}
                  public boolean isAlive() { return true; }
                }
                """)
            .build(tempDir, "initted.jar");

    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    int exitCode =
        InitCommand.run(
            List.of(jar.toString(), "--out-dir", outDir.toString()), new PrintStream(buffer));
    assertEquals(0, exitCode);

    Path moduleYaml = outDir.resolve("gimle-module.yaml");
    Path deploymentYaml = outDir.resolve("deployment.yaml");
    assertTrue(Files.exists(moduleYaml));
    assertTrue(Files.exists(deploymentYaml));

    String moduleContent = Files.readString(moduleYaml);
    assertTrue(moduleContent.contains("com.example.initted.MyProbe"));
    assertTrue(moduleContent.contains("name: com.example.initted"));

    String deploymentContent = Files.readString(deploymentYaml);
    assertTrue(deploymentContent.contains("kind: Deployment"));
    assertTrue(deploymentContent.contains("name: com.example.initted"));
    // The generated file isn't directly consumable by `hilmir deploy` (that command wants a
    // Bundle wrapper), so its own header must say so -- see BundleParser's matching error message
    // for the other half of this fix.
    assertTrue(deploymentContent.contains("not a bundle"));
    assertTrue(deploymentContent.contains("hilmir deploy"));
  }

  @Test
  void generates_the_vessel_form_for_a_launcher_archive_shaped_jar() throws Exception {
    Path outDir = Files.createDirectory(tempDir.resolve("out2"));
    Path jar =
        HilmirTestJarBuilder.create()
            .withMainClass("org.springframework.boot.loader.JarLauncher")
            .withExtraEntry("BOOT-INF/classes/", new byte[0])
            .withExtraEntry("BOOT-INF/lib/", new byte[0])
            .build(tempDir, "fat.jar");

    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    int exitCode =
        InitCommand.run(
            List.of(jar.toString(), "--out-dir", outDir.toString()), new PrintStream(buffer));
    assertEquals(0, exitCode);

    assertTrue(Files.exists(outDir.resolve("deployment.yaml")));
    assertTrue(Files.notExists(outDir.resolve("gimle-module.yaml")));

    String content = Files.readString(outDir.resolve("deployment.yaml"));
    assertTrue(content.contains("vessel:"));
    assertTrue(content.contains("vessel-shaped"));
  }

  @Test
  void never_overwrites_an_existing_file() throws Exception {
    Path outDir = Files.createDirectory(tempDir.resolve("out3"));
    Path sentinel = outDir.resolve("deployment.yaml");
    String sentinelContent = "# hand-edited, do not touch\n";
    Files.writeString(sentinel, sentinelContent, StandardCharsets.UTF_8);

    Path jar =
        HilmirTestJarBuilder.create()
            .withModuleInfo(HilmirTestJarBuilder.minimalModuleInfo("com.example.collide"))
            .build(tempDir, "collide.jar");

    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    assertThrows(
        HilmirException.class,
        () ->
            InitCommand.run(
                List.of(jar.toString(), "--out-dir", outDir.toString()), new PrintStream(buffer)));

    assertEquals(sentinelContent, Files.readString(sentinel));
    assertTrue(Files.notExists(outDir.resolve("gimle-module.yaml")));
  }
}
