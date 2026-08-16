package com.gimle.module.lifecycle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.gimle.core.module.ModuleId;
import com.gimle.core.module.Version;
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
}
