package com.gimle.module.lifecycle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.core.module.ModuleId;
import com.gimle.core.module.Version;
import java.util.Map;
import java.util.Optional;
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
        new ModuleId("com.gimle.echo", Version.parse("1.0.0")),
        Echoer.class,
        value -> "echo:" + value);
    SimpleModuleContext ctx =
        new SimpleModuleContext(new ModuleId("com.gimle.caller", Version.parse("1.0.0")), registry);

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
        new SimpleModuleContext(new ModuleId("com.gimle.caller", Version.parse("1.0.0")), registry);

    assertEquals(
        Optional.empty(),
        ctx.invokeServiceByName(
            "com.gimle.example.NoSuchInterface", 1, "whatever", new String[0], new Object[0]));
  }

  @Test
  void invoke_service_by_name_propagates_a_thrown_application_exception() {
    SimpleServiceRegistry registry = new SimpleServiceRegistry();
    registry.register(
        new ModuleId("com.gimle.echo", Version.parse("1.0.0")),
        Echoer.class,
        value -> {
          throw new IllegalStateException("boom: " + value);
        });
    SimpleModuleContext ctx =
        new SimpleModuleContext(new ModuleId("com.gimle.caller", Version.parse("1.0.0")), registry);

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
            new ModuleId("com.gimle.web", Version.parse("1.0.0")), new SimpleServiceRegistry());

    assertEquals(Map.of(), ctx.reportedPorts());
  }

  @Test
  void a_reported_port_is_retrievable_under_its_own_name() {
    SimpleModuleContext ctx =
        new SimpleModuleContext(
            new ModuleId("com.gimle.web", Version.parse("1.0.0")), new SimpleServiceRegistry());

    ctx.reportPort("HTTP_PORT", 8080);

    assertEquals(Map.of("HTTP_PORT", 8080), ctx.reportedPorts());
  }

  @Test
  void reporting_the_same_name_twice_replaces_the_earlier_value_rather_than_accumulating() {
    SimpleModuleContext ctx =
        new SimpleModuleContext(
            new ModuleId("com.gimle.web", Version.parse("1.0.0")), new SimpleServiceRegistry());

    ctx.reportPort("HTTP_PORT", 8080);
    ctx.reportPort("HTTP_PORT", 8081);

    assertEquals(Map.of("HTTP_PORT", 8081), ctx.reportedPorts());
  }

  @Test
  void multiple_reported_ports_are_all_retrievable_by_their_own_names() {
    SimpleModuleContext ctx =
        new SimpleModuleContext(
            new ModuleId("com.gimle.web", Version.parse("1.0.0")), new SimpleServiceRegistry());

    ctx.reportPort("HTTP_PORT", 8080);
    ctx.reportPort("ADMIN_PORT", 9090);

    assertEquals(Map.of("HTTP_PORT", 8080, "ADMIN_PORT", 9090), ctx.reportedPorts());
  }

  @Test
  void reporting_a_blank_name_is_rejected() {
    SimpleModuleContext ctx =
        new SimpleModuleContext(
            new ModuleId("com.gimle.web", Version.parse("1.0.0")), new SimpleServiceRegistry());

    assertThrows(IllegalArgumentException.class, () -> ctx.reportPort(" ", 8080));
  }

  @Test
  void reporting_a_port_outside_the_valid_range_is_rejected() {
    SimpleModuleContext ctx =
        new SimpleModuleContext(
            new ModuleId("com.gimle.web", Version.parse("1.0.0")), new SimpleServiceRegistry());

    assertThrows(IllegalArgumentException.class, () -> ctx.reportPort("HTTP_PORT", 0));
    assertThrows(IllegalArgumentException.class, () -> ctx.reportPort("HTTP_PORT", 70000));
  }

  @Test
  void reported_ports_returned_to_a_caller_are_a_snapshot_not_a_live_view() {
    SimpleModuleContext ctx =
        new SimpleModuleContext(
            new ModuleId("com.gimle.web", Version.parse("1.0.0")), new SimpleServiceRegistry());
    ctx.reportPort("HTTP_PORT", 8080);

    Map<String, Integer> snapshot = ctx.reportedPorts();
    ctx.reportPort("ADMIN_PORT", 9090);

    assertTrue(
        snapshot.size() == 1,
        "a previously taken snapshot must not observe a port reported afterward");
  }
}
