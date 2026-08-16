package com.gimle.hilmir.release;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.core.protocol.Json;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReleasesCommandTest {

  @TempDir Path tempDir;
  private FakeControlPlane fake;

  @AfterEach
  void tearDown() {
    if (fake != null) {
      fake.close();
    }
  }

  private static PrintStream capture(ByteArrayOutputStream buffer) {
    return new PrintStream(buffer, true, StandardCharsets.UTF_8);
  }

  private Path writeBundle(String name, String bundleName) throws IOException {
    Path file = tempDir.resolve(name);
    Files.writeString(
        file, "kind: Bundle\nname: " + bundleName + "\nversion: 1.0.0\n", StandardCharsets.UTF_8);
    return file;
  }

  @Test
  void lists_only_the_releases_own_meta_rows_filtered_from_the_rest_of_the_config_keyspace()
      throws Exception {
    fake = new FakeControlPlane();
    DeployCommand.run(
        List.of("-f", writeBundle("a.yaml", "release-a").toString(), "--server", fake.address()),
        capture(new ByteArrayOutputStream()));
    DeployCommand.run(
        List.of("-f", writeBundle("b.yaml", "release-b").toString(), "--server", fake.address()),
        capture(new ByteArrayOutputStream()));

    ByteArrayOutputStream out = new ByteArrayOutputStream();
    int exitCode =
        ReleasesCommand.run(List.of("--server", fake.address(), "-o", "json"), capture(out));

    assertEquals(0, exitCode);
    List<Object> rows = Json.asArray(Json.parse(out.toString(StandardCharsets.UTF_8).trim()));
    assertEquals(2, rows.size());
    List<String> names = rows.stream().map(r -> (String) Json.asObject(r).get("name")).toList();
    assertTrue(names.contains("release-a"));
    assertTrue(names.contains("release-b"));
  }

  @Test
  void reports_no_releases_found_for_an_empty_ledger() throws Exception {
    fake = new FakeControlPlane();
    ByteArrayOutputStream out = new ByteArrayOutputStream();

    ReleasesCommand.run(List.of("--server", fake.address()), capture(out));

    assertTrue(out.toString(StandardCharsets.UTF_8).contains("No releases found"));
  }
}
