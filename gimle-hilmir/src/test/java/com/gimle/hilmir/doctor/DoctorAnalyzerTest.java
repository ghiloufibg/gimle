package com.gimle.hilmir.doctor;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.hilmir.analyze.ArtifactScan;
import com.gimle.hilmir.analyze.ArtifactScanner;
import com.gimle.hilmir.analyze.testsupport.HilmirTestJarBuilder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DoctorAnalyzerTest {

  @TempDir Path tempDir;

  private static final String GOOD_DESCRIPTOR =
      """
      name: com.example.clean
      version: 1.0.0
      isolation:
        tier: TIER_1
      resources:
        request:
          memory: 32Mi
          cpu: 20m
        limit:
          memory: 64Mi
          cpu: 100m
      """;

  @Test
  void a_clean_module_shaped_jar_has_no_error_findings() throws Exception {
    Path jar =
        HilmirTestJarBuilder.create()
            .withModuleInfo(HilmirTestJarBuilder.minimalModuleInfo("com.example.clean"))
            .withDescriptor(GOOD_DESCRIPTOR)
            .withClass(
                "com.example.clean.Widget",
                """
                package com.example.clean;
                public class Widget {
                  public Widget() {}
                }
                """)
            .build(tempDir, "clean.jar");

    List<DoctorFinding> findings = analyze(jar, DoctorHostingIntent.MODULE);
    assertFalse(hasError(findings), "expected no errors, got: " + findings);
  }

  @Test
  void a_war_is_rejected_as_unsupported_packaging() throws Exception {
    Path war = tempDir.resolve("app.war");
    Files.writeString(war, "not a jar", StandardCharsets.UTF_8);

    List<DoctorFinding> findings = analyze(war, DoctorHostingIntent.MODULE);
    assertTrue(findings.stream().anyMatch(f -> f.code().equals("UNSUPPORTED_PACKAGING")));
    assertTrue(hasError(findings));
  }

  @Test
  void a_launcher_archive_is_an_error_under_module_intent_but_info_under_vessel_intent()
      throws Exception {
    Path jar =
        HilmirTestJarBuilder.create()
            .withMainClass("org.springframework.boot.loader.JarLauncher")
            .withExtraEntry("BOOT-INF/classes/", new byte[0])
            .withExtraEntry("BOOT-INF/lib/", new byte[0])
            .build(tempDir, "fat.jar");

    List<DoctorFinding> moduleFindings = analyze(jar, DoctorHostingIntent.MODULE);
    DoctorFinding moduleFinding =
        moduleFindings.stream()
            .filter(f -> f.code().equals("NOT_LAYER_HOSTABLE"))
            .findFirst()
            .orElseThrow();
    assertTrue(moduleFinding.severity() == DoctorSeverity.ERROR);

    List<DoctorFinding> vesselFindings = analyze(jar, DoctorHostingIntent.VESSEL);
    DoctorFinding vesselFinding =
        vesselFindings.stream()
            .filter(f -> f.code().equals("NOT_LAYER_HOSTABLE"))
            .findFirst()
            .orElseThrow();
    assertTrue(vesselFinding.severity() == DoctorSeverity.INFO);
    assertFalse(hasError(vesselFindings));
  }

  @Test
  void resource_limit_below_request_is_flagged_incoherent() throws Exception {
    String descriptor =
        """
        name: com.example.badresources
        version: 1.0.0
        isolation:
          tier: TIER_1
        resources:
          request:
            memory: 128Mi
            cpu: 100m
          limit:
            memory: 64Mi
            cpu: 50m
        """;
    Path jar =
        HilmirTestJarBuilder.create()
            .withModuleInfo(HilmirTestJarBuilder.minimalModuleInfo("com.example.badresources"))
            .withDescriptor(descriptor)
            .build(tempDir, "badresources.jar");

    List<DoctorFinding> findings = analyze(jar, DoctorHostingIntent.MODULE);
    assertTrue(findings.stream().anyMatch(f -> f.code().equals("RESOURCES_INCOHERENT")));
  }

  @Test
  void tier_3_is_rejected() throws Exception {
    String descriptor =
        """
        name: com.example.tier3
        version: 1.0.0
        isolation:
          tier: TIER_3
        resources:
          request:
            memory: 32Mi
            cpu: 20m
          limit:
            memory: 64Mi
            cpu: 100m
        """;
    Path jar =
        HilmirTestJarBuilder.create()
            .withModuleInfo(HilmirTestJarBuilder.minimalModuleInfo("com.example.tier3"))
            .withDescriptor(descriptor)
            .build(tempDir, "tier3.jar");

    List<DoctorFinding> findings = analyze(jar, DoctorHostingIntent.MODULE);
    assertTrue(findings.stream().anyMatch(f -> f.code().equals("TIER3_REQUESTED")));
  }

  @Test
  void a_missing_probe_class_is_flagged_invalid() throws Exception {
    String descriptor =
        """
        name: com.example.missingprobe
        version: 1.0.0
        isolation:
          tier: TIER_1
        resources:
          request:
            memory: 32Mi
            cpu: 20m
          limit:
            memory: 64Mi
            cpu: 100m
        health:
          liveness: com.example.missingprobe.NoSuchClass
        """;
    Path jar =
        HilmirTestJarBuilder.create()
            .withModuleInfo(HilmirTestJarBuilder.minimalModuleInfo("com.example.missingprobe"))
            .withDescriptor(descriptor)
            .build(tempDir, "missingprobe.jar");

    List<DoctorFinding> findings = analyze(jar, DoctorHostingIntent.MODULE);
    assertTrue(findings.stream().anyMatch(f -> f.code().equals("PROBE_INVALID")));
  }

  @Test
  void a_valid_liveness_probe_is_not_flagged() throws Exception {
    String descriptor =
        """
        name: com.example.goodprobe
        version: 1.0.0
        isolation:
          tier: TIER_1
        resources:
          request:
            memory: 32Mi
            cpu: 20m
          limit:
            memory: 64Mi
            cpu: 100m
        health:
          liveness: com.example.goodprobe.MyProbe
        """;
    Path jar =
        HilmirTestJarBuilder.create()
            .withModuleInfo(HilmirTestJarBuilder.minimalModuleInfo("com.example.goodprobe"))
            .withDescriptor(descriptor)
            // A stand-in interface compiled into the same fixture, under the real interface's own
            // binary name -- the scanner only ever compares names, so this is sufficient without a
            // gimle-module dependency.
            .withClass(
                "com.gimle.module.probe.LivenessProbe",
                """
                package com.gimle.module.probe;
                public interface LivenessProbe { boolean isAlive(); }
                """)
            .withClass(
                "com.example.goodprobe.MyProbe",
                """
                package com.example.goodprobe;
                import com.gimle.module.probe.LivenessProbe;
                public class MyProbe implements LivenessProbe {
                  public MyProbe() {}
                  public boolean isAlive() { return true; }
                }
                """)
            .build(tempDir, "goodprobe.jar");

    List<DoctorFinding> findings = analyze(jar, DoctorHostingIntent.MODULE);
    assertFalse(findings.stream().anyMatch(f -> f.code().equals("PROBE_INVALID")), "" + findings);
  }

  @Test
  void system_exit_is_an_error_under_module_intent_and_a_warning_under_vessel_intent()
      throws Exception {
    Path jar =
        HilmirTestJarBuilder.create()
            .withModuleInfo(HilmirTestJarBuilder.minimalModuleInfo("com.example.exiter"))
            .withDescriptor(HilmirTestJarBuilder.minimalDescriptor("com.example.exiter", "1.0.0"))
            .withClass(
                "com.example.exiter.Main",
                """
                package com.example.exiter;
                public class Main {
                  public Main() {}
                  void boom() { System.exit(1); }
                }
                """)
            .build(tempDir, "exiter.jar");

    List<DoctorFinding> moduleFindings = analyze(jar, DoctorHostingIntent.MODULE);
    assertTrue(
        moduleFindings.stream()
            .anyMatch(
                f -> f.code().equals("CALLS_SYSTEM_EXIT") && f.severity() == DoctorSeverity.ERROR));

    List<DoctorFinding> vesselFindings = analyze(jar, DoctorHostingIntent.VESSEL);
    assertTrue(
        vesselFindings.stream()
            .anyMatch(
                f ->
                    f.code().equals("CALLS_SYSTEM_EXIT")
                        && f.severity() == DoctorSeverity.WARNING));
  }

  @Test
  void a_flat_non_modular_jar_under_module_intent_is_a_warning_not_an_error() throws Exception {
    Path jar =
        HilmirTestJarBuilder.create()
            .withClass(
                "com.example.flat.Widget",
                """
                package com.example.flat;
                public class Widget {
                  public Widget() {}
                }
                """)
            .build(tempDir, "flat.jar");

    List<DoctorFinding> findings = analyze(jar, DoctorHostingIntent.MODULE);
    assertTrue(findings.stream().anyMatch(f -> f.code().equals("NOT_A_MODULE")));
    assertFalse(
        findings.stream()
            .anyMatch(
                f -> f.code().equals("NOT_A_MODULE") && f.severity() == DoctorSeverity.ERROR));
  }

  @Test
  void split_package_is_detected_across_two_jars() throws Exception {
    Path jarA =
        HilmirTestJarBuilder.create()
            .withClass(
                "com.example.shared.A",
                "package com.example.shared; public class A { public A() {} }")
            .build(tempDir, "a.jar");
    Path jarB =
        HilmirTestJarBuilder.create()
            .withClass(
                "com.example.shared.B",
                "package com.example.shared; public class B { public B() {} }")
            .build(tempDir, "b.jar");

    ArtifactScan primary = ArtifactScanner.scan(jarA);
    ArtifactScan extra = ArtifactScanner.scan(jarB);
    List<DoctorFinding> findings =
        DoctorAnalyzer.analyze(primary, List.of(extra), DoctorHostingIntent.VESSEL);
    assertTrue(findings.stream().anyMatch(f -> f.code().equals("SPLIT_PACKAGE")));
  }

  private static List<DoctorFinding> analyze(Path jar, DoctorHostingIntent intent) {
    ArtifactScan scan = ArtifactScanner.scan(jar);
    return DoctorAnalyzer.analyze(scan, List.of(), intent);
  }

  private static boolean hasError(List<DoctorFinding> findings) {
    return findings.stream().anyMatch(f -> f.severity() == DoctorSeverity.ERROR);
  }
}
