package com.gimle.ragnarok.surtr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.ragnarok.RagnarokException;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** Parsing and validation of workload documents, plus the bundled reference workload. */
final class SurtrWorkloadParserTest {

  private static SurtrWorkload parse(final String yaml) {
    return SurtrWorkloadParser.parse(
        new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));
  }

  @Test
  void parses_a_create_workload_with_measurements_and_gates() {
    final SurtrWorkload workload =
        parse(
            """
            name: demo
            topology: topologies/surtr-density.yaml
            jobs:
              - name: fill
                type: create
                iterations: 20
                qps: 5
                burst: 10
                objects:
                  - kind: deployment
                    name: "d-{{iteration}}"
                    module: greeter-provider
                    replicas: 1
            measurements:
              - instanceStartupLatency
              - apiLatency
            gates:
              maxFailedSubmissions: 0
              maxNeverActive: 0
              instanceActiveP99Millis: 30000
            gc: true
            """);
    assertEquals("demo", workload.name());
    assertEquals(1, workload.jobs().size());
    assertEquals(SurtrJob.Type.CREATE, workload.jobs().get(0).type());
    assertEquals(20, workload.jobs().get(0).iterations());
    assertTrue(workload.gc());
    assertEquals(2, workload.measurements().size());
    assertEquals(30000L, workload.gates().latency().get("instanceActiveP99Millis"));
  }

  @Test
  void a_create_job_without_objects_is_rejected() {
    assertThrows(
        RagnarokException.class,
        () ->
            parse(
                """
                name: bad
                topology: t.yaml
                jobs:
                  - name: fill
                    type: create
                    iterations: 5
                    qps: 1
                    burst: 1
                """));
  }

  @Test
  void an_unknown_measurement_is_rejected() {
    assertThrows(
        RagnarokException.class,
        () ->
            parse(
                """
                name: bad
                topology: t.yaml
                jobs:
                  - name: fill
                    type: create
                    iterations: 1
                    qps: 1
                    burst: 1
                    objects:
                      - kind: deployment
                        name: d
                        module: greeter-provider
                measurements:
                  - notAMeasurement
                """));
  }

  @Test
  void a_workload_without_jobs_is_rejected() {
    assertThrows(RagnarokException.class, () -> parse("name: empty\ntopology: t.yaml\njobs: []\n"));
  }

  @Test
  void the_bundled_module_density_workload_parses() {
    final SurtrWorkload workload = SurtrWorkloadParser.resolve("module-density");
    assertEquals("module-density", workload.name());
    assertEquals("topologies/surtr-density.yaml", workload.topology());
    assertTrue(workload.measurements().contains(Measurement.PLACEMENT_SPREAD));
  }
}
