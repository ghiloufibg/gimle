package com.gimle.module.lifecycle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.core.module.ModuleId;
import com.gimle.core.module.ModuleInstanceId;
import com.gimle.core.module.Version;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Coverage for {@link SimpleModuleContext#invokeServiceByName}'s delegation to its backing {@link
 * ServiceRegistry}.
 */
class SimpleModuleContextTest {

  private interface Echoer {
    String echo(String value);
  }

  @Test
  void invoke_service_by_name_delegates_to_the_backing_registry_and_returns_its_result()
      throws Throwable {
    SimpleServiceRegistry registry = new SimpleServiceRegistry();
    registry.register(
        ModuleInstanceId.unattached(new ModuleId("com.gimle.echo", Version.parse("1.0.0"))),
        Echoer.class,
        value -> "echo:" + value);
    SimpleModuleContext ctx =
        new SimpleModuleContext(
            ModuleInstanceId.unattached(new ModuleId("com.gimle.caller", Version.parse("1.0.0"))),
            registry);

    Optional<Object> result =
        ctx.invokeServiceByName(
            Echoer.class.getName(),
            1,
            "echo",
            new String[] {"java.lang.String"},
            new Object[] {"x"});

    assertEquals(Optional.of("echo:x"), result);
  }

  @Test
  void invoke_service_by_name_on_an_unknown_interface_returns_empty() throws Throwable {
    SimpleServiceRegistry registry = new SimpleServiceRegistry();
    SimpleModuleContext ctx =
        new SimpleModuleContext(
            ModuleInstanceId.unattached(new ModuleId("com.gimle.caller", Version.parse("1.0.0"))),
            registry);

    assertEquals(
        Optional.empty(),
        ctx.invokeServiceByName(
            "com.gimle.example.NoSuchInterface", 1, "whatever", new String[0], new Object[0]));
  }

  @Test
  void invoke_service_by_name_propagates_a_thrown_application_exception() {
    SimpleServiceRegistry registry = new SimpleServiceRegistry();
    registry.register(
        ModuleInstanceId.unattached(new ModuleId("com.gimle.echo", Version.parse("1.0.0"))),
        Echoer.class,
        value -> {
          throw new IllegalStateException("boom: " + value);
        });
    SimpleModuleContext ctx =
        new SimpleModuleContext(
            ModuleInstanceId.unattached(new ModuleId("com.gimle.caller", Version.parse("1.0.0"))),
            registry);

    assertThrows(
        IllegalStateException.class,
        () ->
            ctx.invokeServiceByName(
                Echoer.class.getName(),
                1,
                "echo",
                new String[] {"java.lang.String"},
                new Object[] {"x"}));
  }

  @Test
  void reported_ports_are_empty_until_a_module_reports_one() {
    SimpleModuleContext ctx =
        new SimpleModuleContext(
            ModuleInstanceId.unattached(new ModuleId("com.gimle.web", Version.parse("1.0.0"))),
            new SimpleServiceRegistry());

    assertEquals(Map.of(), ctx.reportedPorts());
  }

  @Test
  void a_reported_port_is_retrievable_under_its_own_name() {
    SimpleModuleContext ctx =
        new SimpleModuleContext(
            ModuleInstanceId.unattached(new ModuleId("com.gimle.web", Version.parse("1.0.0"))),
            new SimpleServiceRegistry());

    ctx.reportPort("HTTP_PORT", 8080);

    assertEquals(Map.of("HTTP_PORT", 8080), ctx.reportedPorts());
  }

  @Test
  void reporting_the_same_name_twice_replaces_the_earlier_value_rather_than_accumulating() {
    SimpleModuleContext ctx =
        new SimpleModuleContext(
            ModuleInstanceId.unattached(new ModuleId("com.gimle.web", Version.parse("1.0.0"))),
            new SimpleServiceRegistry());

    ctx.reportPort("HTTP_PORT", 8080);
    ctx.reportPort("HTTP_PORT", 8081);

    assertEquals(Map.of("HTTP_PORT", 8081), ctx.reportedPorts());
  }

  @Test
  void multiple_reported_ports_are_all_retrievable_by_their_own_names() {
    SimpleModuleContext ctx =
        new SimpleModuleContext(
            ModuleInstanceId.unattached(new ModuleId("com.gimle.web", Version.parse("1.0.0"))),
            new SimpleServiceRegistry());

    ctx.reportPort("HTTP_PORT", 8080);
    ctx.reportPort("ADMIN_PORT", 9090);

    assertEquals(Map.of("HTTP_PORT", 8080, "ADMIN_PORT", 9090), ctx.reportedPorts());
  }

  @Test
  void reporting_a_blank_name_is_rejected() {
    SimpleModuleContext ctx =
        new SimpleModuleContext(
            ModuleInstanceId.unattached(new ModuleId("com.gimle.web", Version.parse("1.0.0"))),
            new SimpleServiceRegistry());

    assertThrows(IllegalArgumentException.class, () -> ctx.reportPort(" ", 8080));
  }

  @Test
  void reporting_a_port_outside_the_valid_range_is_rejected() {
    SimpleModuleContext ctx =
        new SimpleModuleContext(
            ModuleInstanceId.unattached(new ModuleId("com.gimle.web", Version.parse("1.0.0"))),
            new SimpleServiceRegistry());

    assertThrows(IllegalArgumentException.class, () -> ctx.reportPort("HTTP_PORT", 0));
    assertThrows(IllegalArgumentException.class, () -> ctx.reportPort("HTTP_PORT", 70000));
  }

  @Test
  void config_keys_enumerate_every_delivered_key_as_a_snapshot() {
    Map<String, String> configValues = new java.util.concurrent.ConcurrentHashMap<>();
    configValues.put("db.url", "jdbc:h2:mem:");
    SimpleModuleContext ctx =
        new SimpleModuleContext(
            ModuleInstanceId.unattached(new ModuleId("com.gimle.web", Version.parse("1.0.0"))),
            new SimpleServiceRegistry(),
            configValues);

    Set<String> keys = ctx.configKeys();
    configValues.put("db.password", "hunter2");

    assertEquals(Set.of("db.url"), keys);
    assertEquals(Set.of("db.url", "db.password"), ctx.configKeys());
  }

  @Test
  void instance_info_is_empty_when_no_identity_collaborator_was_wired() {
    SimpleModuleContext ctx =
        new SimpleModuleContext(
            ModuleInstanceId.unattached(new ModuleId("com.gimle.web", Version.parse("1.0.0"))),
            new SimpleServiceRegistry());

    assertEquals(Optional.empty(), ctx.instanceInfo());
  }

  @Test
  void instance_info_reads_the_wired_supplier_live_on_every_call() {
    java.util.concurrent.atomic.AtomicReference<Optional<ModuleContext.InstanceInfo>> identity =
        new java.util.concurrent.atomic.AtomicReference<>(Optional.empty());
    SimpleModuleContext ctx =
        new SimpleModuleContext(
            ModuleInstanceId.unattached(new ModuleId("com.gimle.web", Version.parse("1.0.0"))),
            new SimpleServiceRegistry(),
            new java.util.concurrent.ConcurrentHashMap<>(),
            Map.of(),
            SimpleModuleContext.readOnly(path -> new ModuleContext.RelayResult(501, "unused")),
            identity::get);

    assertEquals(Optional.empty(), ctx.instanceInfo());

    ModuleContext.InstanceInfo registered =
        new ModuleContext.InstanceInfo("orders-service", 2, "node-a", Optional.of("acme"));
    identity.set(Optional.of(registered));

    assertEquals(Optional.of(registered), ctx.instanceInfo());
    assertEquals("orders-service", ctx.instanceInfo().orElseThrow().deploymentName());
    assertEquals(2, ctx.instanceInfo().orElseThrow().instanceIndex());
    assertEquals("node-a", ctx.instanceInfo().orElseThrow().nodeId());
    assertEquals(Optional.of("acme"), ctx.instanceInfo().orElseThrow().tenantId());
  }

  @Test
  void named_data_directories_resolve_by_name_and_the_no_arg_accessor_needs_a_sole_volume() {
    SimpleModuleContext multiVolume =
        new SimpleModuleContext(
            ModuleInstanceId.unattached(new ModuleId("com.gimle.web", Version.parse("1.0.0"))),
            new SimpleServiceRegistry(),
            new java.util.concurrent.ConcurrentHashMap<>(),
            Map.of(
                "data", java.nio.file.Path.of("/var/gimle/volumes/orders/0/data"),
                "wal", java.nio.file.Path.of("/var/gimle/volumes/orders/0/wal")));

    assertEquals(
        Optional.of(java.nio.file.Path.of("/var/gimle/volumes/orders/0/wal")),
        multiVolume.dataDirectory("wal"));
    assertEquals(Optional.empty(), multiVolume.dataDirectory("nope"));
    // Two volumes: the no-arg shorthand cannot pick one, so it answers empty by design.
    assertEquals(Optional.empty(), multiVolume.dataDirectory());

    SimpleModuleContext soleVolume =
        new SimpleModuleContext(
            ModuleInstanceId.unattached(new ModuleId("com.gimle.web", Version.parse("1.0.0"))),
            new SimpleServiceRegistry(),
            new java.util.concurrent.ConcurrentHashMap<>(),
            Map.of("data", java.nio.file.Path.of("/var/gimle/volumes/orders/0/data")));
    assertEquals(
        Optional.of(java.nio.file.Path.of("/var/gimle/volumes/orders/0/data")),
        soleVolume.dataDirectory());
  }

  @Test
  void reported_ports_returned_to_a_caller_are_a_snapshot_not_a_live_view() {
    SimpleModuleContext ctx =
        new SimpleModuleContext(
            ModuleInstanceId.unattached(new ModuleId("com.gimle.web", Version.parse("1.0.0"))),
            new SimpleServiceRegistry());
    ctx.reportPort("HTTP_PORT", 8080);

    Map<String, Integer> snapshot = ctx.reportedPorts();
    ctx.reportPort("ADMIN_PORT", 9090);

    assertTrue(
        snapshot.size() == 1,
        "a previously taken snapshot must not observe a port reported afterward");
  }

  /**
   * No TLS material exists in a plaintext cluster, so the context a hosted module would dial a
   * TLS-terminating listener with is absent -- a plain socket is the right thing to open.
   */
  @Test
  void client_ssl_context_is_empty_in_a_plaintext_cluster() {
    String previous = System.getProperty("gimle.transport.protocol");
    try {
      System.clearProperty("gimle.transport.protocol");
      SimpleModuleContext ctx =
          new SimpleModuleContext(
              ModuleInstanceId.unattached(new ModuleId("com.gimle.caller", Version.parse("1.0.0"))),
              new SimpleServiceRegistry());

      assertEquals(Optional.empty(), ctx.clientSslContext());
    } finally {
      if (previous == null) {
        System.clearProperty("gimle.transport.protocol");
      } else {
        System.setProperty("gimle.transport.protocol", previous);
      }
    }
  }
}
