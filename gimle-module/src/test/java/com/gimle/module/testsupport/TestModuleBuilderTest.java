package com.gimle.module.testsupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;

class TestModuleBuilderTest {

  // NEVER: once a jar is opened via ModuleFinder/defineModulesWithOneLoader, the JDK's internal
  // module reader keeps the file handle open with no public API to force-close it. On Windows
  // that means JUnit's post-test directory cleanup fails with "file in use" — a real platform
  // constraint (not a test bug), so tests that load a built jar as a module opt out of cleanup.
  @TempDir(cleanup = CleanupMode.NEVER)
  Path tempDir;

  @Test
  void builds_a_loadable_jar_with_module_info_and_descriptor() throws IOException {
    Path jar =
        TestModuleBuilder.module(
                """
                module com.gimle.fixture.greet {
                  exports com.gimle.fixture.greet;
                }
                """)
            .withClass(
                "com.gimle.fixture.greet.Greeter",
                """
                package com.gimle.fixture.greet;
                public class Greeter {
                  public String greet() {
                    return "hello";
                  }
                }
                """)
            .withDescriptor(TestModuleBuilder.minimalDescriptor("com.gimle.fixture.greet", "1.0.0"))
            .build(tempDir, "greet.jar");

    assertTrue(java.nio.file.Files.isRegularFile(jar));

    try (JarFile jarFile = new JarFile(jar.toFile())) {
      assertTrue(jarFile.getJarEntry("module-info.class") != null, "module-info.class present");
      assertTrue(
          jarFile.getJarEntry("com/gimle/fixture/greet/Greeter.class") != null,
          "class file present");
      assertTrue(
          jarFile.getJarEntry("META-INF/gimle/gimle-module.yaml") != null, "descriptor present");
    }
  }

  @Test
  void compiled_class_is_actually_loadable_and_runnable() throws Exception {
    Path jar =
        TestModuleBuilder.module(
                """
                module com.gimle.fixture.calc {
                  exports com.gimle.fixture.calc;
                }
                """)
            .withClass(
                "com.gimle.fixture.calc.Calc",
                """
                package com.gimle.fixture.calc;
                public class Calc {
                  public int add(int a, int b) {
                    return a + b;
                  }
                }
                """)
            .withDescriptor(TestModuleBuilder.minimalDescriptor("com.gimle.fixture.calc", "1.0.0"))
            .build(tempDir, "calc.jar");

    java.lang.module.ModuleFinder finder = java.lang.module.ModuleFinder.of(jar);
    java.lang.module.Configuration cf =
        java.lang.module.Configuration.resolve(
            finder,
            List.of(ModuleLayer.boot().configuration()),
            java.lang.module.ModuleFinder.of(),
            List.of("com.gimle.fixture.calc"));
    ModuleLayer layer =
        ModuleLayer.defineModulesWithOneLoader(
                cf, List.of(ModuleLayer.boot()), ClassLoader.getSystemClassLoader())
            .layer();
    ClassLoader loader = layer.findLoader("com.gimle.fixture.calc");
    Class<?> calcClass = loader.loadClass("com.gimle.fixture.calc.Calc");
    Object instance = calcClass.getDeclaredConstructor().newInstance();
    Object result = calcClass.getMethod("add", int.class, int.class).invoke(instance, 3, 4);
    assertEquals(7, result);
  }
}
