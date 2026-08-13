package com.gimle.module.lifecycle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
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

  private static ModuleId id(String name, String version) {
    return new ModuleId(name, Version.parse(version));
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

  @Test
  void lookup_prefers_the_highest_ready_version_over_a_lower_ready_version() {
    // A hot redeploy leaves both the old and new version registered under the same
    // interface at once (see HotRedeployTest); cutover must be atomic per lookup, not blended.
    SimpleServiceRegistry registry = new SimpleServiceRegistry();
    Greeter oldVersion = () -> "old";
    Greeter newVersion = () -> "new";
    registry.register(id("com.gimle.greeter", "1.0.0"), Greeter.class, oldVersion);
    registry.register(id("com.gimle.greeter", "2.0.0"), Greeter.class, newVersion);

    for (int i = 0; i < 10; i++) {
      assertEquals(Optional.of(newVersion), registry.lookup(Greeter.class));
    }
  }

  @Test
  void lookup_falls_back_to_a_lower_version_while_the_highest_version_has_no_ready_entry() {
    SimpleServiceRegistry registry = new SimpleServiceRegistry();
    ModuleId oldOwner = id("com.gimle.greeter", "1.0.0");
    ModuleId newOwner = id("com.gimle.greeter", "2.0.0");
    Greeter oldVersion = () -> "old";
    Greeter newVersion = () -> "new";
    registry.register(oldOwner, Greeter.class, oldVersion);
    registry.register(newOwner, Greeter.class, newVersion);
    registry.markUnready(newOwner); // e.g. still starting up

    for (int i = 0; i < 5; i++) {
      assertEquals(Optional.of(oldVersion), registry.lookup(Greeter.class));
    }

    registry.markReady(newOwner);
    assertEquals(Optional.of(newVersion), registry.lookup(Greeter.class));
  }

  @Test
  void lookup_round_robins_within_the_preferred_version_only() {
    SimpleServiceRegistry registry = new SimpleServiceRegistry();
    Greeter oldVersion = () -> "old";
    Greeter newA = () -> "new-a";
    Greeter newB = () -> "new-b";
    registry.register(id("com.gimle.greeter.old", "1.0.0"), Greeter.class, oldVersion);
    registry.register(id("com.gimle.greeter.a", "2.0.0"), Greeter.class, newA);
    registry.register(id("com.gimle.greeter.b", "2.0.0"), Greeter.class, newB);

    Set<Greeter> seen = new HashSet<>();
    for (int i = 0; i < 10; i++) {
      seen.add(registry.lookup(Greeter.class).orElseThrow());
    }
    assertEquals(Set.of(newA, newB), seen, "only the two 2.0.0 providers should ever be selected");
  }

  @Test
  void
      lookup_by_interface_name_deterministically_prefers_the_highest_version_regardless_of_registration_order()
          throws Exception {
    // The interface itself can be a distinct Class object per module when it's loaded inside each
    // module's own isolated ModuleLayer rather than a shared platform layer -- findInterface must
    // not depend on HashMap iteration order to decide which Class (and therefore which owner) it
    // resolves to.
    Class<?> ifaceFromLoaderA = defineIsolatedCopy(DuplicatedGreeter.class);
    Class<?> ifaceFromLoaderB = defineIsolatedCopy(DuplicatedGreeter.class);
    assertNotSame(ifaceFromLoaderA, ifaceFromLoaderB, "sanity: must be two distinct Class objects");

    Object lowVersionInstance = newProxy(ifaceFromLoaderA);
    Object highVersionInstance = newProxy(ifaceFromLoaderB);

    // Registration order deliberately puts the *lower* version's Class in the map first -- if
    // findInterface just returned the first keySet() match, this would return the wrong owner.
    SimpleServiceRegistry registry = new SimpleServiceRegistry();
    registerRaw(registry, id("com.gimle.dup", "1.0.0"), ifaceFromLoaderA, lowVersionInstance);
    registerRaw(registry, id("com.gimle.dup", "2.0.0"), ifaceFromLoaderB, highVersionInstance);

    assertEquals(
        Optional.of(highVersionInstance),
        registry.lookupByInterfaceName(DuplicatedGreeter.class.getName()));
  }

  @SuppressWarnings("unchecked")
  private static void registerRaw(
      SimpleServiceRegistry registry, ModuleId owner, Class<?> iface, Object instance) {
    registry.register(owner, (Class<Object>) iface, instance);
  }

  private static Object newProxy(Class<?> iface) {
    return java.lang.reflect.Proxy.newProxyInstance(
        iface.getClassLoader(), new Class<?>[] {iface}, (proxy, method, args) -> "proxied");
  }

  /**
   * Reads {@code type}'s own class bytes off the test classpath and redefines them under a fresh,
   * otherwise-delegating class loader -- the standard trick for getting a second, distinct {@link
   * Class} object for the same binary name without standing up a full {@code ModuleLayer}.
   */
  private static Class<?> defineIsolatedCopy(Class<?> type) throws Exception {
    String resourceName = type.getName().replace('.', '/') + ".class";
    byte[] bytes;
    try (var in = type.getClassLoader().getResourceAsStream(resourceName)) {
      bytes = in.readAllBytes();
    }
    ClassLoader isolated =
        new ClassLoader(type.getClassLoader()) {
          @Override
          protected Class<?> findClass(String name) throws ClassNotFoundException {
            if (!name.equals(type.getName())) {
              throw new ClassNotFoundException(name);
            }
            return defineClass(name, bytes, 0, bytes.length);
          }

          @Override
          protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (name.equals(type.getName())) {
              synchronized (getClassLoadingLock(name)) {
                Class<?> found = findLoadedClass(name);
                if (found == null) {
                  found = findClass(name);
                }
                if (resolve) {
                  resolveClass(found);
                }
                return found;
              }
            }
            return super.loadClass(name, resolve);
          }
        };
    return Class.forName(type.getName(), true, isolated);
  }
}
