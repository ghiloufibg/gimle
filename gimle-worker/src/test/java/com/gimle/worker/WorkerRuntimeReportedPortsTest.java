package com.gimle.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.gimle.core.module.ModuleId;
import com.gimle.core.module.Version;
import com.gimle.module.testsupport.TestModuleBuilder;
import com.gimle.worker.testsupport.WiredWorkerRuntime;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;

/**
 * Proves the real threading a hosted module's {@code ModuleContext#reportPort} call needs to reach
 * {@link WorkerRuntime#reportedPortsFor} -- {@code ModuleController} creates one {@code
 * SimpleModuleContext} per resolved module and hands the same instance to every hook callback, so
 * whatever a real {@code onStart} reports is exactly what a later {@code reportedPortsFor} lookup
 * against that same {@code ModuleId} sees, with no copying gap in between. This is the layer {@code
 * WorkerMain}'s own periodic metrics report loop reads through, so proving it here (rather than
 * only at {@link com.gimle.module.lifecycle.SimpleModuleContext} unit-test granularity) is what
 * confirms the wiring end to end, not just each piece in isolation.
 */
class WorkerRuntimeReportedPortsTest {

  @TempDir(cleanup = CleanupMode.NEVER)
  Path tempDir;

  @Test
  void a_modules_onstart_reported_port_reaches_the_worker_runtime() {
    Path jar =
        TestModuleBuilder.module(
                """
                module com.gimle.fixture.portreporting {
                }
                """)
            .withDescriptor(
                """
                name: com.gimle.fixture.portreporting
                version: 1.0.0
                isolation:
                  tier: TIER_1
                resources:
                  request:
                    memory: 16Mi
                    cpu: 10m
                  limit:
                    memory: 32Mi
                    cpu: 50m
                lifecycle:
                  hooks: com.gimle.worker.testsupport.PortReportingHooks
                """)
            .build(tempDir, "portreporting.jar");

    WiredWorkerRuntime.Result f =
        WiredWorkerRuntime.start(
            jar,
            99,
            Optional.empty(),
            exhaustedId -> {},
            new InstanceIdentityRegistry(),
            identity -> {});

    assertEquals(Map.of("HTTP_PORT", 8080), f.runtime().reportedPortsFor(f.id()));
  }

  @Test
  void a_module_that_never_calls_reportport_yields_an_empty_ports_map() {
    Path jar =
        TestModuleBuilder.module(
                """
                module com.gimle.fixture.noportsreported {
                }
                """)
            .withDescriptor(
                TestModuleBuilder.minimalDescriptor("com.gimle.fixture.noportsreported", "1.0.0"))
            .build(tempDir, "noportsreported.jar");

    WiredWorkerRuntime.Result f =
        WiredWorkerRuntime.start(
            jar,
            99,
            Optional.empty(),
            exhaustedId -> {},
            new InstanceIdentityRegistry(),
            identity -> {});

    assertEquals(Map.of(), f.runtime().reportedPortsFor(f.id()));
  }

  @Test
  void reported_ports_for_an_unknown_module_id_is_also_an_empty_map() {
    Path jar =
        TestModuleBuilder.module(
                """
                module com.gimle.fixture.otherid {
                }
                """)
            .withDescriptor(
                TestModuleBuilder.minimalDescriptor("com.gimle.fixture.otherid", "1.0.0"))
            .build(tempDir, "otherid.jar");

    WiredWorkerRuntime.Result f =
        WiredWorkerRuntime.start(
            jar,
            99,
            Optional.empty(),
            exhaustedId -> {},
            new InstanceIdentityRegistry(),
            identity -> {});

    ModuleId unrelated = new ModuleId("com.gimle.fixture.doesnotexist", Version.parse("1.0.0"));
    assertEquals(Map.of(), f.runtime().reportedPortsFor(unrelated));
  }
}
