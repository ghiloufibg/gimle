package com.gimle.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.core.logging.InstanceMdcKeys;
import com.gimle.core.module.ModuleId;
import com.gimle.core.module.Version;
import com.gimle.module.lifecycle.SimpleServiceRegistry;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

class InstanceTaggingServiceRegistryTest {

  public interface Greeter {
    String greet();
  }

  private static final ModuleId ID =
      new ModuleId("com.gimle.example.orders", Version.parse("1.0.0"));

  private final SimpleServiceRegistry delegate = new SimpleServiceRegistry();
  private final InstanceIdentityRegistry identities = new InstanceIdentityRegistry();
  private final InstanceTaggingServiceRegistry tagging =
      new InstanceTaggingServiceRegistry(delegate, identities);

  @AfterEach
  void clearMdc() {
    MDC.clear();
  }

  @Test
  void registers_untagged_when_no_identity_is_known_for_the_owner() {
    Greeter original = () -> "hi";

    tagging.register(ID, Greeter.class, original);

    assertSame(original, delegate.lookup(Greeter.class).orElseThrow());
  }

  @Test
  void registers_a_tagging_proxy_when_identity_is_known() {
    identities.register(ID, new InstanceIdentity("orders-prod", 2, Optional.empty()));
    AtomicReference<String> observedDuringCall = new AtomicReference<>();
    Greeter original =
        () -> {
          observedDuringCall.set(MDC.get(InstanceMdcKeys.DEPLOYMENT_NAME));
          return "hi";
        };

    tagging.register(ID, Greeter.class, original);
    Greeter registered = delegate.lookup(Greeter.class).orElseThrow();

    assertNotSame(original, registered, "a known identity must be wrapped in a tagging proxy");
    assertEquals("hi", registered.greet());
    assertEquals("orders-prod", observedDuringCall.get());
    assertNull(MDC.get(InstanceMdcKeys.DEPLOYMENT_NAME), "MDC must not leak past the call");
  }

  @Test
  void lookup_and_mark_unready_delegate_directly() {
    Greeter original = () -> "hi";
    tagging.register(ID, Greeter.class, original);

    assertTrue(tagging.lookup(Greeter.class).isPresent());
    tagging.markUnready(ID);
    assertTrue(tagging.lookup(Greeter.class).isEmpty());
  }

  @Test
  void remove_delegates_and_clears_the_identity_registry() {
    identities.register(ID, new InstanceIdentity("orders-prod", 0, Optional.empty()));
    tagging.register(ID, Greeter.class, () -> "hi");

    tagging.remove(ID);

    assertTrue(delegate.lookup(Greeter.class).isEmpty());
    assertTrue(identities.lookup(ID).isEmpty());
  }
}
