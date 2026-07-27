package com.gimle.core.logging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

class InstanceMdcContextTest {

  public interface Greeter {
    String greet(String name);
  }

  @AfterEach
  void clearMdc() {
    MDC.clear();
  }

  @Test
  void tags_for_omits_tenant_id_when_null() {
    Map<String, String> tags =
        InstanceMdcContext.tagsFor("orders-prod", 3, "orders-service", "1.4.2", null);

    assertEquals("orders-prod", tags.get(InstanceMdcKeys.DEPLOYMENT_NAME));
    assertEquals("3", tags.get(InstanceMdcKeys.INSTANCE_INDEX));
    assertEquals("orders-service", tags.get(InstanceMdcKeys.MODULE_ID));
    assertEquals("1.4.2", tags.get(InstanceMdcKeys.MODULE_VERSION));
    assertFalse(tags.containsKey(InstanceMdcKeys.TENANT_ID));
  }

  @Test
  void tags_for_includes_tenant_id_when_present() {
    Map<String, String> tags =
        InstanceMdcContext.tagsFor("orders-prod", 3, "orders-service", "1.4.2", "acme-corp");

    assertEquals("acme-corp", tags.get(InstanceMdcKeys.TENANT_ID));
  }

  @Test
  void tag_proxy_sets_mdc_during_the_call_and_restores_it_afterward() {
    Map<String, String> tags =
        InstanceMdcContext.tagsFor("orders-prod", 0, "orders", "1.0.0", null);
    AtomicReference<String> observedDuringCall = new AtomicReference<>();
    Greeter target =
        name -> {
          observedDuringCall.set(MDC.get(InstanceMdcKeys.DEPLOYMENT_NAME));
          return "hi " + name;
        };

    MDC.put("unrelated", "still-here");
    Greeter proxy = InstanceMdcContext.tagProxy(Greeter.class, target, tags);
    String result = proxy.greet("world");

    assertEquals("hi world", result);
    assertEquals("orders-prod", observedDuringCall.get());
    assertNull(MDC.get(InstanceMdcKeys.DEPLOYMENT_NAME), "MDC must not leak past the call");
    assertEquals("still-here", MDC.get("unrelated"), "caller's own MDC state is preserved");
  }

  @Test
  void tag_proxy_restores_mdc_even_when_the_call_throws() {
    Map<String, String> tags =
        InstanceMdcContext.tagsFor("orders-prod", 0, "orders", "1.0.0", null);
    Greeter target =
        name -> {
          throw new IllegalStateException("boom");
        };

    Greeter proxy = InstanceMdcContext.tagProxy(Greeter.class, target, tags);

    IllegalStateException thrown =
        assertThrows(IllegalStateException.class, () -> proxy.greet("world"));
    assertEquals("boom", thrown.getMessage());
    assertNull(MDC.get(InstanceMdcKeys.DEPLOYMENT_NAME));
  }

  @Test
  void run_tagged_sets_and_restores_mdc_around_a_plain_task() throws Exception {
    Map<String, String> tags =
        InstanceMdcContext.tagsFor("orders-prod", 1, "orders", "1.0.0", null);

    String observed =
        InstanceMdcContext.runTagged(tags, () -> MDC.get(InstanceMdcKeys.DEPLOYMENT_NAME));

    assertEquals("orders-prod", observed);
    assertNull(MDC.get(InstanceMdcKeys.DEPLOYMENT_NAME));
  }

  @Test
  void run_tagged_restores_previous_mdc_value_rather_than_just_clearing() throws Exception {
    MDC.put(InstanceMdcKeys.DEPLOYMENT_NAME, "outer");
    Map<String, String> tags = InstanceMdcContext.tagsFor("inner", 0, "orders", "1.0.0", null);

    InstanceMdcContext.runTagged(tags, () -> MDC.get(InstanceMdcKeys.DEPLOYMENT_NAME));

    assertTrue(MDC.get(InstanceMdcKeys.DEPLOYMENT_NAME).equals("outer"));
  }
}
