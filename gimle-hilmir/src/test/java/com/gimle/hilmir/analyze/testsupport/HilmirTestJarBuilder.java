package com.gimle.hilmir.analyze.testsupport;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;

/**
 * Compiles real {@code .java} sources in-process via {@code javax.tools.JavaCompiler} and
 * hand-packs a real jar via {@code JarOutputStream} -- the same recipe {@code gimle-module}'s own
 * {@code TestModuleBuilder} established, extended for what {@code doctor}/{@code init}'s own tests
 * need that fixture builder has no way to do: an optional {@code module-info.java} (omitted
 * entirely produces a genuinely non-modular, vessel-shaped jar, rather than always compiling one),
 * a jar manifest with {@code Main-Class}/{@code Implementation-Version}, and raw non-source entries
 * (nested-launcher-archive markers, fake native library files) that don't correspond to real
 * compiled classes at all.
 */
public final class HilmirTestJarBuilder {

  private String moduleInfoSource;
  private final List<SourceFile> sources = new ArrayList<>();
  private String descriptorYaml;
  private String mainClass;
  private String implementationVersion;
  private final Map<String, byte[]> extraEntries = new LinkedHashMap<>();

  private HilmirTestJarBuilder() {}

  public static HilmirTestJarBuilder create() {
    return new HilmirTestJarBuilder();
  }

  /** Omit entirely for a non-modular (vessel-shaped) fixture jar. */
  public HilmirTestJarBuilder withModuleInfo(String moduleInfoSource) {
    this.moduleInfoSource = moduleInfoSource;
    return this;
  }

  public HilmirTestJarBuilder withClass(String binaryName, String javaSource) {
    sources.add(new SourceFile(binaryName, javaSource));
    return this;
  }

  public HilmirTestJarBuilder withDescriptor(String yaml) {
    this.descriptorYaml = yaml;
    return this;
  }

  public HilmirTestJarBuilder withMainClass(String mainClass) {
    this.mainClass = mainClass;
    return this;
  }

  public HilmirTestJarBuilder withImplementationVersion(String version) {
    this.implementationVersion = version;
    return this;
  }

  /**
   * A raw jar entry not backed by a compiled class -- launcher-archive markers, fake native libs.
   */
  public HilmirTestJarBuilder withExtraEntry(String entryName, byte[] content) {
    extraEntries.put(entryName, content);
    return this;
  }

  public HilmirTestJarBuilder withExtraEntry(String entryName, String content) {
    return withExtraEntry(entryName, content.getBytes(StandardCharsets.UTF_8));
  }

  /** Compiles one standalone class (no module-info) and returns its raw {@code .class} bytes. */
  public static byte[] compileClass(String binaryName, String javaSource) {
    Path classesDir = null;
    try {
      classesDir = Files.createTempDirectory("gimle-hilmir-test-classes-");
      HilmirTestJarBuilder builder = new HilmirTestJarBuilder().withClass(binaryName, javaSource);
      builder.compile(classesDir);
      Path classFile = classesDir.resolve(binaryName.replace('.', '/') + ".class");
      return Files.readAllBytes(classFile);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    } finally {
      if (classesDir != null) {
        deleteRecursively(classesDir);
      }
    }
  }

  public static String minimalModuleInfo(String moduleName) {
    return "module " + moduleName + " {}";
  }

  public static String minimalDescriptor(String name, String version) {
    return """
        name: %s
        version: %s
        isolation:
          tier: TIER_1
        resources:
          request:
            memory: 16Mi
            cpu: 10m
          limit:
            memory: 32Mi
            cpu: 50m
        """
        .formatted(name, version);
  }

  public Path build(Path outputDir, String jarFileName) {
    Path classesDir = null;
    try {
      Files.createDirectories(outputDir);
      classesDir = Files.createTempDirectory("gimle-hilmir-test-classes-");
      if (!sources.isEmpty() || moduleInfoSource != null) {
        compile(classesDir);
      }
      Path jarPath = outputDir.resolve(jarFileName);
      writeJar(classesDir, jarPath);
      return jarPath;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    } finally {
      // Compiler output, not the jar itself -- nothing keeps this directory open once the jar has
      // been written, unlike the module-layer jars this produces (kept around under a deliberate
      // @TempDir(NEVER) at call sites, since a live classloader may still hold one open).
      if (classesDir != null) {
        deleteRecursively(classesDir);
      }
    }
  }

  private void compile(Path classesDir) throws IOException {
    JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
    if (compiler == null) {
      throw new IllegalStateException(
          "no system Java compiler available; tests must run on a JDK, not a JRE");
    }
    DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
    try (StandardJavaFileManager fileManager =
        compiler.getStandardFileManager(diagnostics, Locale.ROOT, StandardCharsets.UTF_8)) {
      fileManager.setLocation(StandardLocation.CLASS_OUTPUT, List.of(classesDir.toFile()));

      List<JavaFileObject> units = new ArrayList<>();
      if (moduleInfoSource != null) {
        units.add(new InMemorySource("module-info", moduleInfoSource));
      }
      for (SourceFile sf : sources) {
        units.add(new InMemorySource(sf.binaryName(), sf.source()));
      }

      JavaCompiler.CompilationTask task =
          compiler.getTask(null, fileManager, diagnostics, List.of("--release", "25"), null, units);
      if (!task.call()) {
        String messages =
            diagnostics.getDiagnostics().stream()
                .map(Object::toString)
                .collect(Collectors.joining("\n"));
        throw new IllegalStateException("test fixture jar failed to compile:\n" + messages);
      }
    }
  }

  private void writeJar(Path classesDir, Path jarPath) throws IOException {
    java.util.jar.Manifest manifest = new java.util.jar.Manifest();
    manifest.getMainAttributes().put(java.util.jar.Attributes.Name.MANIFEST_VERSION, "1.0");
    if (mainClass != null) {
      manifest.getMainAttributes().put(java.util.jar.Attributes.Name.MAIN_CLASS, mainClass);
    }
    if (implementationVersion != null) {
      manifest
          .getMainAttributes()
          .put(java.util.jar.Attributes.Name.IMPLEMENTATION_VERSION, implementationVersion);
    }
    try (java.util.jar.JarOutputStream jarOut =
        new java.util.jar.JarOutputStream(Files.newOutputStream(jarPath), manifest)) {
      if (Files.isDirectory(classesDir)) {
        try (var walk = Files.walk(classesDir)) {
          for (Path file : walk.filter(Files::isRegularFile).sorted().toList()) {
            String entryName = classesDir.relativize(file).toString().replace('\\', '/');
            jarOut.putNextEntry(new java.util.jar.JarEntry(entryName));
            Files.copy(file, jarOut);
            jarOut.closeEntry();
          }
        }
      }
      if (descriptorYaml != null) {
        jarOut.putNextEntry(new java.util.jar.JarEntry("META-INF/gimle/gimle-module.yaml"));
        jarOut.write(descriptorYaml.getBytes(StandardCharsets.UTF_8));
        jarOut.closeEntry();
      }
      for (Map.Entry<String, byte[]> extra : extraEntries.entrySet()) {
        jarOut.putNextEntry(new java.util.jar.JarEntry(extra.getKey()));
        jarOut.write(extra.getValue());
        jarOut.closeEntry();
      }
    }
  }

  private static void deleteRecursively(Path root) {
    if (!Files.exists(root)) {
      return;
    }
    try (var walk = Files.walk(root)) {
      walk.sorted(Comparator.reverseOrder())
          .forEach(
              path -> {
                try {
                  Files.deleteIfExists(path);
                } catch (IOException e) {
                  throw new UncheckedIOException(e);
                }
              });
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private record SourceFile(String binaryName, String source) {}

  private static final class InMemorySource extends SimpleJavaFileObject {
    private final String content;

    InMemorySource(String binaryNameOrModuleInfo, String content) {
      super(
          URI.create(
              "string:///" + binaryNameOrModuleInfo.replace('.', '/') + Kind.SOURCE.extension),
          Kind.SOURCE);
      this.content = content;
    }

    @Override
    public CharSequence getCharContent(boolean ignoreEncodingErrors) {
      return content;
    }
  }
}
