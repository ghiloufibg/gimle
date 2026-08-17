package com.gimle.module.testsupport;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.stream.Collectors;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;

/**
 * Compiles a real, tiny JPMS module (module-info.java + classes + gimle-module.yaml) into a jar,
 * entirely in-process via {@code javax.tools.JavaCompiler}. Used to build hermetic test fixtures
 * for resolver/ModuleLayer/lifecycle tests that need actual loadable module jars rather than mocks
 * — those tests are exercising real dynamic classloading, which can't be faked.
 */
public final class TestModuleBuilder {

  private final String moduleInfoSource;
  private final List<SourceFile> sources = new ArrayList<>();
  private final List<Path> modulePathJars = new ArrayList<>();
  private String descriptorYaml;

  private TestModuleBuilder(String moduleInfoSource) {
    this.moduleInfoSource = moduleInfoSource;
  }

  public static TestModuleBuilder module(String moduleInfoSource) {
    return new TestModuleBuilder(moduleInfoSource);
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

  public TestModuleBuilder withClass(String binaryName, String javaSource) {
    sources.add(new SourceFile(binaryName, javaSource));
    return this;
  }

  public TestModuleBuilder withDescriptor(String yaml) {
    this.descriptorYaml = yaml;
    return this;
  }

  /** Puts the given jars on the compiler's module path, for modules requiring other modules. */
  public TestModuleBuilder dependsOn(Path... jars) {
    modulePathJars.addAll(List.of(jars));
    return this;
  }

  public Path build(Path outputDir, String jarFileName) {
    Path classesDir = null;
    try {
      Files.createDirectories(outputDir);
      classesDir = Files.createTempDirectory("gimle-test-classes-");
      compile(classesDir);
      Path jarPath = outputDir.resolve(jarFileName);
      writeJar(classesDir, jarPath);
      return jarPath;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    } finally {
      // Compiler output, not the jar itself -- unlike the module-layer jar this produces (kept
      // around under a deliberate @TempDir(NEVER) at the call site, since a live classloader may
      // still hold it open), nothing keeps this directory open once the jar has been written.
      if (classesDir != null) {
        deleteRecursively(classesDir);
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
      if (!modulePathJars.isEmpty()) {
        fileManager.setLocation(
            StandardLocation.MODULE_PATH, modulePathJars.stream().map(Path::toFile).toList());
      }

      List<JavaFileObject> units = new ArrayList<>();
      units.add(new InMemorySource("module-info", moduleInfoSource));
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
        throw new IllegalStateException("test fixture module failed to compile:\n" + messages);
      }
    }
  }

  private void writeJar(Path classesDir, Path jarPath) throws IOException {
    Manifest manifest = new Manifest();
    manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
    try (JarOutputStream jarOut = new JarOutputStream(Files.newOutputStream(jarPath), manifest)) {
      try (var walk = Files.walk(classesDir)) {
        for (Path file : walk.filter(Files::isRegularFile).sorted().toList()) {
          String entryName = classesDir.relativize(file).toString().replace('\\', '/');
          jarOut.putNextEntry(new JarEntry(entryName));
          Files.copy(file, jarOut);
          jarOut.closeEntry();
        }
      }
      if (descriptorYaml != null) {
        jarOut.putNextEntry(new JarEntry("META-INF/gimle/gimle-module.yaml"));
        jarOut.write(descriptorYaml.getBytes(StandardCharsets.UTF_8));
        jarOut.closeEntry();
      }
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
