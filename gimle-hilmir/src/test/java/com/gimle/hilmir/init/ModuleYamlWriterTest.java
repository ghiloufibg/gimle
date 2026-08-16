package com.gimle.hilmir.init;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.core.module.IsolationTier;
import com.gimle.core.module.ResourceSpec;
import com.gimle.core.module.Version;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Verifies {@link ModuleYamlWriter}'s generated text actually parses back through the same schema
 * {@code ModuleDescriptorParser} expects, field by field, using {@code gimle-core}'s own {@code
 * Version}/{@code IsolationTier}/{@code ResourceSpec} types to validate each value -- the closest
 * check available without depending on {@code gimle-module} for the real parser itself.
 */
class ModuleYamlWriterTest {

  @Test
  void generated_yaml_parses_back_with_expected_fields() {
    String yaml =
        ModuleYamlWriter.render(
            new ModuleYamlWriter.Detected(
                "com.example.gen",
                true,
                Optional.of("com.example.gen.MyLiveness"),
                Optional.of("com.example.gen.MyReadiness"),
                Optional.of("com.example.gen.MyHooks"),
                Optional.empty()));

    Map<?, ?> root = load(yaml);
    assertEquals("com.example.gen", root.get("name"));
    assertEquals(Version.parse("0.1.0"), Version.parse((String) root.get("version")));

    Map<?, ?> isolation = (Map<?, ?>) root.get("isolation");
    assertEquals(IsolationTier.TIER_1, IsolationTier.valueOf((String) isolation.get("tier")));

    Map<?, ?> resources = (Map<?, ?>) root.get("resources");
    Map<?, ?> request = (Map<?, ?>) resources.get("request");
    Map<?, ?> limit = (Map<?, ?>) resources.get("limit");
    ResourceSpec requestSpec =
        new ResourceSpec((String) request.get("memory"), (String) request.get("cpu"));
    ResourceSpec limitSpec =
        new ResourceSpec((String) limit.get("memory"), (String) limit.get("cpu"));
    assertTrue(requestSpec.memoryBytes() <= limitSpec.memoryBytes());
    assertTrue(requestSpec.cpuMillicores() <= limitSpec.cpuMillicores());

    Map<?, ?> health = (Map<?, ?>) root.get("health");
    assertEquals("com.example.gen.MyLiveness", health.get("liveness"));
    assertEquals("com.example.gen.MyReadiness", health.get("readiness"));

    Map<?, ?> lifecycle = (Map<?, ?>) root.get("lifecycle");
    assertEquals("com.example.gen.MyHooks", lifecycle.get("hooks"));
  }

  @Test
  void a_name_derived_from_the_file_name_carries_a_todo_comment_but_still_parses() {
    String yaml =
        ModuleYamlWriter.render(
            new ModuleYamlWriter.Detected(
                "some-app",
                false,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty()));
    assertTrue(yaml.contains("TODO"));
    Map<?, ?> root = load(yaml);
    assertEquals("some-app", root.get("name"));
  }

  private static Map<?, ?> load(String yaml) {
    Yaml parser = new Yaml(new SafeConstructor(new LoaderOptions()));
    return (Map<?, ?>) parser.load(yaml);
  }
}
