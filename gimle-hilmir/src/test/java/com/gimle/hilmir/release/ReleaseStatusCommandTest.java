package com.gimle.hilmir.release;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.core.protocol.Json;
import com.gimle.hilmir.HilmirException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReleaseStatusCommandTest {

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

  private Path writeBundle() throws IOException {
    Path file = tempDir.resolve("bundle.yaml");
    Files.writeString(
        file,
        """
        kind: Bundle
        name: greeter-suite
        version: 1.0.0
        workloads:
          - manifest: |
              kind: Deployment
              name: greeter-provider
        """,
        StandardCharsets.UTF_8);
    return file;
  }

  @Test
  void reports_meta_plus_each_resources_live_status() throws Exception {
    fake = new FakeControlPlane();
    DeployCommand.run(
        List.of("-f", writeBundle().toString(), "--server", fake.address()),
        capture(new ByteArrayOutputStream()));
    fake.setInstances(
        "Deployment",
        "greeter-provider",
        List.of(Map.of("observation", Map.of("lifecycleState", "ACTIVE"))));

    ByteArrayOutputStream out = new ByteArrayOutputStream();
    int exitCode =
        ReleaseStatusCommand.run(
            List.of("greeter-suite", "--server", fake.address(), "-o", "json"), capture(out));

    assertEquals(0, exitCode);
    Map<String, Object> body =
        Json.asObject(Json.parse(out.toString(StandardCharsets.UTF_8).trim()));
    assertEquals("greeter-suite", body.get("name"));
    assertEquals(1L, ((Number) body.get("currentRevision")).longValue());
    List<Object> resources = Json.asArray(body.get("resources"));
    assertEquals(1, resources.size());
    Map<String, Object> resource = Json.asObject(resources.get(0));
    assertEquals("greeter-provider", resource.get("name"));
    Map<String, Object> status = Json.asObject(resource.get("status"));
    List<Object> instances = Json.asArray(status.get("instances"));
    assertEquals(1, instances.size());
  }

  @Test
  void fails_cleanly_for_a_nonexistent_release() throws Exception {
    fake = new FakeControlPlane();

    HilmirException e =
        assertThrows(
            HilmirException.class,
            () ->
                ReleaseStatusCommand.run(
                    List.of("no-such-release", "--server", fake.address()),
                    capture(new ByteArrayOutputStream())));
    assertTrue(e.getMessage().contains("no release named"));
  }
}
