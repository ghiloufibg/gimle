package com.gimle.hilmir.release;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ValueOverridesTest {

  @TempDir Path tempDir;

  @Test
  void bundle_defaults_win_when_nothing_overrides_them() {
    Map<String, String> merged =
        ValueOverrides.merge(Map.of("apiToken", "default"), Optional.empty(), List.of());
    assertEquals("default", merged.get("apiToken"));
  }

  @Test
  void a_values_file_overrides_the_bundles_own_default() throws IOException {
    Path file = tempDir.resolve("values.yaml");
    Files.writeString(file, "apiToken: from-file\n", StandardCharsets.UTF_8);

    Map<String, String> merged =
        ValueOverrides.merge(Map.of("apiToken", "default"), Optional.of(file), List.of());

    assertEquals("from-file", merged.get("apiToken"));
  }

  @Test
  void a_set_flag_wins_over_both_the_default_and_a_values_file() throws IOException {
    Path file = tempDir.resolve("values.yaml");
    Files.writeString(file, "apiToken: from-file\n", StandardCharsets.UTF_8);

    Map<String, String> merged =
        ValueOverrides.merge(
            Map.of("apiToken", "default"), Optional.of(file), List.of("apiToken=from-set"));

    assertEquals("from-set", merged.get("apiToken"));
  }

  @Test
  void the_last_of_several_set_flags_for_the_same_key_wins() {
    Map<String, String> merged =
        ValueOverrides.merge(Map.of(), Optional.empty(), List.of("k=first", "k=second"));
    assertEquals("second", merged.get("k"));
  }
}
