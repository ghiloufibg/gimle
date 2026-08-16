package com.gimle.hilmir.doctor;

import com.gimle.core.module.ResourceSpec;
import com.gimle.hilmir.analyze.ArtifactPackaging;
import com.gimle.hilmir.analyze.ArtifactScan;
import com.gimle.hilmir.analyze.ClassHazards;
import com.gimle.hilmir.analyze.DescriptorSnapshot;
import com.gimle.hilmir.analyze.JarStructure;
import com.gimle.hilmir.analyze.ProbeInterfaces;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The static (no-cluster-needed) finding catalog: a pure function from an {@link ArtifactScan} (and
 * any additional jars passed alongside it, for the two checks that need more than one artifact) to
 * the list of deployability problems a real deploy would eventually hit. Every finding's {@code
 * code} is part of this class's own public contract, exactly like {@code TopologyValidator}'s own
 * rule codes -- grepped and scripted against, never renamed once shipped.
 *
 * <p>One code beyond the originally scoped catalog: {@code DESCRIPTOR_UNREADABLE} (module-hosting
 * only) for a structurally real module jar (has {@code module-info.class}) whose {@code
 * gimle-module.yaml} is missing or missing a required field -- grounded directly in {@code
 * ModuleArtifactReader}'s own hard failure for exactly this case, not a speculative addition.
 */
public final class DoctorAnalyzer {

  private DoctorAnalyzer() {}

  public static List<DoctorFinding> analyze(
      ArtifactScan primary, List<ArtifactScan> extras, DoctorHostingIntent intent) {
    List<DoctorFinding> findings = new ArrayList<>();

    if (primary.packaging() != ArtifactPackaging.PLAIN_JAR) {
      findings.add(
          error(
              "UNSUPPORTED_PACKAGING",
              "not a plain runnable jar ("
                  + packagingDescription(primary.packaging())
                  + "): "
                  + primary.jarPath()));
      return List.copyOf(findings);
    }

    JarStructure structure = primary.structure().orElseThrow();
    DescriptorSnapshot descriptor = primary.descriptor();

    checkLayerHostability(structure, intent, findings);
    checkModularity(structure, intent, findings);
    checkDescriptorPresence(structure, descriptor, intent, findings);
    checkIsolationTier(descriptor, intent, findings);
    checkResourceCoherence(descriptor, intent, findings);
    checkProbesAndHooks(descriptor, primary.classHazards(), intent, findings);
    checkVersionDrift(structure, descriptor, findings);
    checkNativeCode(structure, primary.classHazards(), findings);
    checkSystemExit(primary.classHazards(), intent, findings);
    checkLeakRisk(primary.classHazards(), findings);
    checkBindsOwnPort(primary.classHazards(), findings);
    checkSplitPackage(primary, extras, findings);
    checkBundledLoggingBinding(primary, extras, findings);

    return List.copyOf(findings);
  }

  private static void checkLayerHostability(
      JarStructure structure, DoctorHostingIntent intent, List<DoctorFinding> out) {
    if (!structure.launcherArchiveShaped()) {
      return;
    }
    if (intent == DoctorHostingIntent.MODULE) {
      out.add(
          error(
              "NOT_LAYER_HOSTABLE",
              "artifact looks like a launcher archive (a nested BOOT-INF/Quarkus-fast-jar-style"
                  + " classpath layout, not a flat set of classes a ModuleLayer can load) -- this"
                  + " cannot be module-hosted; run 'hilmir doctor --vessel' to evaluate it as a"
                  + " vessel instead"));
    } else {
      out.add(
          info(
              "NOT_LAYER_HOSTABLE",
              "artifact is a launcher archive, which is exactly what vessel-hosting expects (the"
                  + " jar's own unmodified launcher runs as-is) -- no ModuleLayer involved"));
    }
  }

  private static void checkModularity(
      JarStructure structure, DoctorHostingIntent intent, List<DoctorFinding> out) {
    if (intent != DoctorHostingIntent.MODULE
        || structure.hasModuleInfo()
        || structure.launcherArchiveShaped()) {
      return;
    }
    out.add(
        warning(
            "NOT_A_MODULE",
            "no module-info.class: this is an ordinary flat jar, not a real JPMS module -- automatic"
                + " modules are rejected by the platform's own artifact reader"));
  }

  private static void checkDescriptorPresence(
      JarStructure structure,
      DescriptorSnapshot descriptor,
      DoctorHostingIntent intent,
      List<DoctorFinding> out) {
    if (intent != DoctorHostingIntent.MODULE || !structure.hasModuleInfo()) {
      return;
    }
    List<String> missing = new ArrayList<>();
    if (!descriptor.present()) {
      missing.add("META-INF/gimle/gimle-module.yaml is missing");
    } else {
      if (!descriptor.parseIssues().isEmpty()) {
        missing.addAll(descriptor.parseIssues());
      }
      if (descriptor.name().isEmpty()) {
        missing.add("'name' is missing or blank");
      }
      if (descriptor.version().isEmpty()
          && descriptor.parseIssues().stream()
              .noneMatch(issue -> issue.startsWith("malformed version"))) {
        missing.add("'version' is missing or blank");
      }
      if (descriptor.isolationTier().isEmpty()
          && descriptor.parseIssues().stream()
              .noneMatch(issue -> issue.startsWith("invalid isolation.tier"))) {
        missing.add("'isolation.tier' is missing");
      }
      if (!descriptor.hasResources()) {
        missing.add("'resources.request'/'resources.limit' (memory and cpu) are required");
      }
    }
    if (!missing.isEmpty()) {
      out.add(
          error(
              "DESCRIPTOR_UNREADABLE",
              "gimle-module.yaml unusable: " + String.join("; ", missing)));
    }
  }

  private static void checkIsolationTier(
      DescriptorSnapshot descriptor, DoctorHostingIntent intent, List<DoctorFinding> out) {
    if (intent != DoctorHostingIntent.MODULE) {
      return;
    }
    descriptor
        .isolationTier()
        .filter(tier -> tier == com.gimle.core.module.IsolationTier.TIER_3)
        .ifPresent(
            tier ->
                out.add(
                    error(
                        "TIER3_REQUESTED",
                        "isolation.tier: TIER_3 is declared, but Tier 3 (namespace isolation) is"
                            + " unimplemented on every platform today and is rejected outright at"
                            + " scheduling time")));
  }

  private static void checkResourceCoherence(
      DescriptorSnapshot descriptor, DoctorHostingIntent intent, List<DoctorFinding> out) {
    if (intent != DoctorHostingIntent.MODULE || !descriptor.hasResources()) {
      return;
    }
    ResourceSpec request;
    ResourceSpec limit;
    try {
      request = new ResourceSpec(descriptor.requestMemory().get(), descriptor.requestCpu().get());
    } catch (IllegalArgumentException e) {
      out.add(
          error(
              "RESOURCES_INCOHERENT", "unparseable resources.request quantity: " + e.getMessage()));
      return;
    }
    try {
      limit = new ResourceSpec(descriptor.limitMemory().get(), descriptor.limitCpu().get());
    } catch (IllegalArgumentException e) {
      out.add(
          error("RESOURCES_INCOHERENT", "unparseable resources.limit quantity: " + e.getMessage()));
      return;
    }
    if (request.memoryBytes() > limit.memoryBytes()
        || request.cpuMillicores() > limit.cpuMillicores()) {
      out.add(
          error(
              "RESOURCES_INCOHERENT",
              "resources.request ("
                  + request.memory()
                  + "/"
                  + request.cpu()
                  + ") exceeds"
                  + " resources.limit ("
                  + limit.memory()
                  + "/"
                  + limit.cpu()
                  + ")"));
    }
  }

  private static void checkProbesAndHooks(
      DescriptorSnapshot descriptor,
      Map<String, ClassHazards> classHazards,
      DoctorHostingIntent intent,
      List<DoctorFinding> out) {
    if (intent != DoctorHostingIntent.MODULE) {
      return;
    }
    checkProbeClass(
        descriptor.livenessClass(),
        ProbeInterfaces.LIVENESS_PROBE,
        "health.liveness",
        classHazards,
        out);
    checkProbeClass(
        descriptor.readinessClass(),
        ProbeInterfaces.READINESS_PROBE,
        "health.readiness",
        classHazards,
        out);
    checkProbeClass(
        descriptor.lifecycleHooksClass(),
        ProbeInterfaces.MODULE_LIFECYCLE_HOOKS,
        "lifecycle.hooks",
        classHazards,
        out);
    checkProbeClass(
        descriptor.jobHooksClass(),
        ProbeInterfaces.JOB_HOOKS,
        "lifecycle.jobHooks",
        classHazards,
        out);
  }

  private static void checkProbeClass(
      Optional<String> className,
      String expectedInterface,
      String fieldPath,
      Map<String, ClassHazards> classHazards,
      List<DoctorFinding> out) {
    if (className.isEmpty()) {
      return;
    }
    ClassHazards hazards = classHazards.get(className.get());
    if (hazards == null) {
      out.add(
          error(
              "PROBE_INVALID",
              fieldPath
                  + " names '"
                  + className.get()
                  + "', but no such class is present in the"
                  + " jar"));
      return;
    }
    if (!hazards.declaredInterfaces().contains(expectedInterface)) {
      out.add(
          error(
              "PROBE_INVALID",
              fieldPath
                  + "'s class '"
                  + className.get()
                  + "' does not directly implement "
                  + expectedInterface));
    }
    if (!hazards.hasNoArgConstructor()) {
      out.add(
          error(
              "PROBE_INVALID",
              fieldPath
                  + "'s class '"
                  + className.get()
                  + "' has no no-arg constructor -- it"
                  + " cannot be instantiated reflectively"));
    }
  }

  private static void checkVersionDrift(
      JarStructure structure, DescriptorSnapshot descriptor, List<DoctorFinding> out) {
    if (descriptor.version().isEmpty() || structure.manifestImplementationVersion().isEmpty()) {
      return;
    }
    String descriptorVersion = descriptor.version().get().toString();
    String manifestVersion = structure.manifestImplementationVersion().get().strip();
    if (!descriptorVersion.equals(manifestVersion)) {
      out.add(
          warning(
              "VERSION_DRIFT",
              "gimle-module.yaml declares version "
                  + descriptorVersion
                  + ", but the jar manifest's"
                  + " own Implementation-Version is "
                  + manifestVersion));
    }
  }

  private static void checkNativeCode(
      JarStructure structure, Map<String, ClassHazards> classHazards, List<DoctorFinding> out) {
    for (String entry : structure.nativeLibraryEntries()) {
      out.add(error("NATIVE_CODE", "bundled native library entry: " + entry));
    }
    forEachHazard(
        classHazards,
        ClassHazards::loadsNativeLibrary,
        className -> out.add(error("NATIVE_CODE", className + " calls System.load/loadLibrary")));
  }

  private static void checkSystemExit(
      Map<String, ClassHazards> classHazards, DoctorHostingIntent intent, List<DoctorFinding> out) {
    DoctorSeverity severity =
        intent == DoctorHostingIntent.MODULE ? DoctorSeverity.ERROR : DoctorSeverity.WARNING;
    forEachHazard(
        classHazards,
        ClassHazards::callsSystemExit,
        className ->
            out.add(
                new DoctorFinding(
                    "CALLS_SYSTEM_EXIT", severity, className + " calls System.exit")));
  }

  private static void checkLeakRisk(
      Map<String, ClassHazards> classHazards, List<DoctorFinding> out) {
    forEachHazard(
        classHazards,
        ClassHazards::registersShutdownHook,
        className -> out.add(warning("LEAK_RISK", className + " registers a JVM shutdown hook")));
    forEachHazard(
        classHazards,
        ClassHazards::constructsNonDaemonThread,
        className ->
            out.add(
                warning(
                    "LEAK_RISK",
                    className + " constructs a Thread without calling setDaemon(true)")));
    forEachHazard(
        classHazards,
        ClassHazards::hasUnshutdownStaticExecutor,
        className ->
            out.add(
                warning(
                    "LEAK_RISK",
                    className
                        + " declares a static ExecutorService field with no visible"
                        + " shutdown/shutdownNow/close call anywhere in the class")));
  }

  private static void checkBindsOwnPort(
      Map<String, ClassHazards> classHazards, List<DoctorFinding> out) {
    forEachHazard(
        classHazards,
        ClassHazards::opensServerSocket,
        className ->
            out.add(
                info(
                    "BINDS_OWN_PORT",
                    className
                        + " opens a server socket -- informational only, the platform has no"
                        + " ingress story for a plain module today")));
  }

  private static void checkSplitPackage(
      ArtifactScan primary, List<ArtifactScan> extras, List<DoctorFinding> out) {
    if (extras.isEmpty()) {
      return;
    }
    List<ArtifactScan> all = new ArrayList<>();
    all.add(primary);
    all.addAll(extras);
    Set<String> reported = new LinkedHashSet<>();
    for (int i = 0; i < all.size(); i++) {
      for (int j = i + 1; j < all.size(); j++) {
        Set<String> overlap = new LinkedHashSet<>(all.get(i).topLevelPackages());
        overlap.retainAll(all.get(j).topLevelPackages());
        for (String pkg : overlap) {
          if (reported.add(pkg)) {
            out.add(
                error(
                    "SPLIT_PACKAGE",
                    "package '"
                        + pkg
                        + "' is present in more than one jar on the same module"
                        + " path: "
                        + all.get(i).jarPath()
                        + " and "
                        + all.get(j).jarPath()));
          }
        }
      }
    }
  }

  private static void checkBundledLoggingBinding(
      ArtifactScan primary, List<ArtifactScan> extras, List<DoctorFinding> out) {
    Set<String> hits = new LinkedHashSet<>();
    primary.structure().ifPresent(s -> hits.addAll(s.loggingBindingHits()));
    for (ArtifactScan extra : extras) {
      extra.structure().ifPresent(s -> hits.addAll(s.loggingBindingHits()));
    }
    for (String hit : hits) {
      out.add(
          warning(
              "BUNDLED_LOGGING_BINDING",
              "a logging binding ("
                  + hit
                  + ") is bundled among the artifact's own dependencies --"
                  + " hosted modules see the platform's logging API through the shared layer and"
                  + " should not bundle their own binding"));
    }
  }

  private static void forEachHazard(
      Map<String, ClassHazards> classHazards,
      java.util.function.Predicate<ClassHazards> predicate,
      java.util.function.Consumer<String> onMatch) {
    classHazards.values().stream().filter(predicate).map(ClassHazards::binaryName).forEach(onMatch);
  }

  private static String packagingDescription(ArtifactPackaging packaging) {
    return switch (packaging) {
      case WAR -> "a WAR";
      case EAR -> "an EAR";
      case DIRECTORY -> "a directory/fast-jar distribution";
      case NOT_A_JAR -> "not a ZIP-shaped file (likely a native image or other non-JVM binary)";
      case PLAIN_JAR -> "a plain jar";
    };
  }

  private static DoctorFinding error(String code, String message) {
    return new DoctorFinding(code, DoctorSeverity.ERROR, message);
  }

  private static DoctorFinding warning(String code, String message) {
    return new DoctorFinding(code, DoctorSeverity.WARNING, message);
  }

  private static DoctorFinding info(String code, String message) {
    return new DoctorFinding(code, DoctorSeverity.INFO, message);
  }
}
