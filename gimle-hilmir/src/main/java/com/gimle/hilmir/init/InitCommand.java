package com.gimle.hilmir.init;

import com.gimle.hilmir.HilmirException;
import com.gimle.hilmir.analyze.ArtifactPackaging;
import com.gimle.hilmir.analyze.ArtifactScan;
import com.gimle.hilmir.analyze.ArtifactScanner;
import com.gimle.hilmir.analyze.ClassHazards;
import com.gimle.hilmir.analyze.JarStructure;
import com.gimle.hilmir.analyze.ProbeInterfaces;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.lang.module.ModuleFinder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * {@code hilmir init <jar> [--out-dir <dir>]}: inspects a built jar (via the exact same {@link
 * ArtifactScanner} {@code doctor} uses -- one analyzer, two consumers) and writes {@code
 * deployment.yaml}, plus {@code gimle-module.yaml} when the jar is module-hosting-shaped, filled in
 * wherever detection can be confident and {@code # TODO}-annotated wherever it can't. Never
 * overwrites a file that already exists at the target path -- refuses outright, listing every
 * colliding path, rather than silently clobbering something the caller may have hand-edited.
 */
public final class InitCommand {

  private static final Pattern TRAILING_VERSION =
      Pattern.compile("-\\d+(?:\\.\\d+){0,2}(?:[.-][A-Za-z0-9]+)?$");

  private InitCommand() {}

  public static int run(List<String> args, PrintStream out) {
    Args parsed = Args.parse(args);
    Path jarPath = Path.of(parsed.jarArg);
    ArtifactScan scan = ArtifactScanner.scan(jarPath);
    if (scan.packaging() != ArtifactPackaging.PLAIN_JAR) {
      throw new HilmirException(
          "hilmir init needs a plain runnable jar to inspect; " + jarPath + " is not one");
    }
    JarStructure structure = scan.structure().orElseThrow();

    Path outDir = parsed.outDir != null ? Path.of(parsed.outDir) : defaultOutDir(jarPath);
    boolean vesselShaped = !structure.hasModuleInfo() || structure.launcherArchiveShaped();

    NameGuess name = guessModuleName(jarPath, structure);
    Path deploymentPath = outDir.resolve("deployment.yaml");
    Path moduleYamlPath = outDir.resolve("gimle-module.yaml");

    List<Path> targets =
        vesselShaped ? List.of(deploymentPath) : List.of(deploymentPath, moduleYamlPath);
    List<Path> existing = targets.stream().filter(Files::exists).toList();
    if (!existing.isEmpty()) {
      throw new HilmirException(
          "refusing to overwrite existing file(s): "
              + existing.stream().map(Path::toString).reduce((a, b) -> a + ", " + b).orElse(""));
    }

    if (vesselShaped) {
      writeFile(deploymentPath, DeploymentYamlWriter.renderVesselForm(name.value(), parsed.jarArg));
      out.println("wrote " + deploymentPath + " (vessel-hosting -- jar is not module-shaped)");
      return 0;
    }

    ProbeDetection probes = detectProbes(scan);
    writeFile(
        moduleYamlPath,
        ModuleYamlWriter.render(
            new ModuleYamlWriter.Detected(
                name.value(),
                name.confident(),
                probes.liveness(),
                probes.readiness(),
                probes.hooks(),
                probes.jobHooks())));
    writeFile(
        deploymentPath,
        DeploymentYamlWriter.renderModuleForm(name.value(), name.confident(), parsed.jarArg));
    out.println("wrote " + moduleYamlPath);
    out.println("wrote " + deploymentPath);
    return 0;
  }

  // toAbsolutePath().getParent() is null only for a filesystem root itself, never for a real
  // jar path -- falling back to the current directory keeps this total rather than throwing on
  // that theoretical case.
  private static Path defaultOutDir(Path jarPath) {
    Path parent = jarPath.toAbsolutePath().getParent();
    return parent != null ? parent : Path.of(".");
  }

  private record NameGuess(String value, boolean confident) {}

  private record ProbeDetection(
      Optional<String> liveness,
      Optional<String> readiness,
      Optional<String> hooks,
      Optional<String> jobHooks) {}

  private static ProbeDetection detectProbes(ArtifactScan scan) {
    Optional<String> liveness = Optional.empty();
    Optional<String> readiness = Optional.empty();
    Optional<String> hooks = Optional.empty();
    Optional<String> jobHooks = Optional.empty();
    for (ClassHazards hazards : scan.classHazards().values()) {
      if (liveness.isEmpty()
          && hazards.declaredInterfaces().contains(ProbeInterfaces.LIVENESS_PROBE)) {
        liveness = Optional.of(hazards.binaryName());
      }
      if (readiness.isEmpty()
          && hazards.declaredInterfaces().contains(ProbeInterfaces.READINESS_PROBE)) {
        readiness = Optional.of(hazards.binaryName());
      }
      if (hooks.isEmpty()
          && hazards.declaredInterfaces().contains(ProbeInterfaces.MODULE_LIFECYCLE_HOOKS)) {
        hooks = Optional.of(hazards.binaryName());
      }
      if (jobHooks.isEmpty() && hazards.declaredInterfaces().contains(ProbeInterfaces.JOB_HOOKS)) {
        jobHooks = Optional.of(hazards.binaryName());
      }
    }
    return new ProbeDetection(liveness, readiness, hooks, jobHooks);
  }

  private static NameGuess guessModuleName(Path jarPath, JarStructure structure) {
    if (structure.hasModuleInfo()) {
      Optional<String> fromModuleInfo = moduleNameFromModuleInfo(jarPath);
      if (fromModuleInfo.isPresent()) {
        return new NameGuess(fromModuleInfo.get(), true);
      }
    }
    Path fileNamePath = jarPath.getFileName();
    String base = fileNamePath == null ? jarPath.toString() : fileNamePath.toString();
    if (base.toLowerCase(Locale.ROOT).endsWith(".jar")) {
      base = base.substring(0, base.length() - ".jar".length());
    }
    base = TRAILING_VERSION.matcher(base).replaceFirst("");
    return new NameGuess(base, false);
  }

  private static Optional<String> moduleNameFromModuleInfo(Path jarPath) {
    try {
      return ModuleFinder.of(jarPath).findAll().stream()
          .findFirst()
          .map(ref -> ref.descriptor().name());
    } catch (RuntimeException e) {
      return Optional.empty();
    }
  }

  private static void writeFile(Path path, String content) {
    try {
      Path parent = path.getParent();
      if (parent != null) {
        Files.createDirectories(parent);
      }
      Files.writeString(path, content, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException("failed to write " + path, e);
    }
  }

  private static final class Args {
    String jarArg;
    String outDir;

    static Args parse(List<String> args) {
      Args parsed = new Args();
      int i = 0;
      while (i < args.size()) {
        String token = args.get(i);
        if (token.equals("--out-dir")) {
          if (i + 1 >= args.size()) {
            throw new HilmirException("--out-dir requires a value");
          }
          parsed.outDir = args.get(i + 1);
          i += 2;
        } else {
          if (parsed.jarArg != null) {
            throw new HilmirException("hilmir init takes exactly one jar argument");
          }
          parsed.jarArg = token;
          i++;
        }
      }
      if (parsed.jarArg == null) {
        throw new HilmirException("usage: hilmir init <jar> [--out-dir <dir>]");
      }
      return parsed;
    }
  }
}
