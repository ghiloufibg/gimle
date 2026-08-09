package com.gimle.module.lifecycle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.core.module.ModuleId;
import com.gimle.core.module.Version;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SimpleServiceRegistryTest {

  private interface Greeter {
    String greet();
  }

  private static ModuleId id(String name) {
    return new ModuleId(name, Version.parse("1.0.0"));
  }

  @Test
  void lookup_with_no_registrations_is_empty() {
    SimpleServiceRegistry registry = new SimpleServiceRegistry();
    assertEquals(Optional.empty(), registry.lookup(Greeter.class));
  }

  @Test
  void registers_and_looks_up_a_single_instance() {
    SimpleServiceRegistry registry = new SimpleServiceRegistry();
    Greeter instance = () -> "hello";
    registry.register(id("com.gimle.a"), Greeter.class, instance);
    assertEquals(Optional.of(instance), registry.lookup(Greeter.class));
  }

  @Test
  void round_robins_across_multiple_ready_providers() {
    SimpleServiceRegistry registry = new SimpleServiceRegistry();
    Greeter a = () -> "a";
    Greeter b = () -> "b";
    registry.register(id("com.gimle.a"), Greeter.class, a);
    registry.register(id("com.gimle.b"), Greeter.class, b);

    Set<Greeter> seen = new HashSet<>();
    for (int i = 0; i < 10; i++) {
      seen.add(registry.lookup(Greeter.class).orElseThrow());
    }
    assertEquals(Set.of(a, b), seen, "round-robin should eventually visit every ready provider");
  }

  @Test
  void re_registering_the_same_owner_replaces_rather_than_duplicates() {
    SimpleServiceRegistry registry = new SimpleServiceRegistry();
    ModuleId owner = id("com.gimle.a");
    Greeter first = () -> "first";
    Greeter second = () -> "second";
    registry.register(owner, Greeter.class, first);
    registry.register(owner, Greeter.class, second);

    // Only "second" should ever be returned -- if re-registration duplicated instead of
    // replacing, round-robin would eventually surface "first" too.
    for (int i = 0; i < 10; i++) {
      assertEquals(Optional.of(second), registry.lookup(Greeter.class));
    }
  }

  @Test
  void mark_unready_excludes_from_lookup_without_removing() {
    SimpleServiceRegistry registry = new SimpleServiceRegistry();
    ModuleId owner = id("com.gimle.a");
    Greeter instance = () -> "hello";
    registry.register(owner, Greeter.class, instance);

    registry.markUnready(owner);
    assertEquals(Optional.empty(), registry.lookup(Greeter.class));
  }

  @Test
  void mark_unready_on_one_provider_leaves_others_selectable() {
    SimpleServiceRegistry registry = new SimpleServiceRegistry();
    ModuleId ownerA = id("com.gimle.a");
    ModuleId ownerB = id("com.gimle.b");
    Greeter a = () -> "a";
    Greeter b = () -> "b";
    registry.register(ownerA, Greeter.class, a);
    registry.register(ownerB, Greeter.class, b);

    registry.markUnready(ownerA);
    for (int i = 0; i < 5; i++) {
      assertEquals(Optional.of(b), registry.lookup(Greeter.class));
    }
  }

  @Test
  void mark_ready_reverses_mark_unready_and_re_enters_the_round_robin() {
    SimpleServiceRegistry registry = new SimpleServiceRegistry();
    ModuleId owner = id("com.gimle.a");
    Greeter instance = () -> "hello";
    registry.register(owner, Greeter.class, instance);

    registry.markUnready(owner);
    assertEquals(Optional.empty(), registry.lookup(Greeter.class));

    registry.markReady(owner);
    assertEquals(Optional.of(instance), registry.lookup(Greeter.class));
  }

  @Test
  void mark_ready_on_an_unknown_owner_is_a_no_op() {
    SimpleServiceRegistry registry = new SimpleServiceRegistry();
    // Never registered anything -- must not throw, and must not conjure a lookupable entry.
    registry.markReady(id("com.gimle.never-registered"));
    assertEquals(Optional.empty(), registry.lookup(Greeter.class));
  }

  @Test
  void remove_excludes_entirely() {
    SimpleServiceRegistry registry = new SimpleServiceRegistry();
    ModuleId owner = id("com.gimle.a");
    registry.register(owner, Greeter.class, () -> "hello");

    registry.remove(owner);
    assertEquals(Optional.empty(), registry.lookup(Greeter.class));
  }

  @Test
  void remove_only_affects_its_own_owner() {
    SimpleServiceRegistry registry = new SimpleServiceRegistry();
    ModuleId ownerA = id("com.gimle.a");
    ModuleId ownerB = id("com.gimle.b");
    Greeter b = () -> "b";
    registry.register(ownerA, Greeter.class, () -> "a");
    registry.register(ownerB, Greeter.class, b);

    registry.remove(ownerA);
    assertEquals(Optional.of(b), registry.lookup(Greeter.class));
  }

  @Test
  void lookup_is_scoped_per_interface() {
    SimpleServiceRegistry registry = new SimpleServiceRegistry();
    registry.register(id("com.gimle.a"), Greeter.class, () -> "hello");
    assertTrue(registry.lookup(Runnable.class).isEmpty());
  }
}
