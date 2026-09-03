package com.gimle.fabric.registry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.core.module.ModuleId;
import com.gimle.core.module.ServiceExport;
import com.gimle.core.module.Version;
import com.gimle.fabric.catalog.ServiceCatalog;
import com.gimle.fabric.cluster.MemberId;
import com.gimle.fabric.transport.FabricServer;
import com.gimle.module.lifecycle.ServiceRegistry;
import com.gimle.module.lifecycle.SimpleServiceRegistry;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.parallel.ResourceAccessMode;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

/**
 * Coverage for {@link FabricServiceRegistry#invokeByName}, the name-driven counterpart to {@link
 * FabricServiceRegistryTest}'s own {@code lookup(Class)} coverage -- a route-config-driven caller
 * (the gateway module) has only plain strings to invoke with, never a compile-time {@code
 * Class<T>}.
 */
// Reads gimle.transport.protocol (through FabricClient/FabricServer) without ever setting it: a
// READ lock lets these plaintext classes run concurrently with each other while excluding any
// class that mutates the JVM-global TLS properties mid-run (see FabricTransportTlsTest).
@ResourceLock(value = Resources.SYSTEM_PROPERTIES, mode = ResourceAccessMode.READ)
class FabricServiceRegistryInvokeByNameTest {

  private static final ServiceExport GREETER_EXPORT =
      new ServiceExport(Greeter.class.getName(), Version.parse("1.0.0"));
  private static final ModuleId OWNER =
      new ModuleId("com.gimle.example.greeter", Version.parse("1.0.0"));

  private final MemberId selfNode =
      new MemberId("node-a", new InetSocketAddress("127.0.0.1", 7946));
  private final List<FabricServer> servers = new ArrayList<>();

  @AfterEach
  void tearDown() {
    servers.forEach(FabricServer::close);
  }

  private FabricServiceRegistry newRegistry(ServiceRegistry localRegistry, ServiceCatalog catalog) {
    return new FabricServiceRegistry(
        selfNode,
        "worker-self",
        localRegistry,
        catalog,
        owner -> List.of(GREETER_EXPORT),
        message -> {},
        Greeter.class.getClassLoader(),
        4,
        0.5,
        Duration.ofMillis(200));
  }

  private FabricServiceRegistry newRegistryForTenant(
      ServiceRegistry localRegistry,
      ServiceCatalog catalog,
      ServiceExport export,
      Optional<String> selfTenantId) {
    return new FabricServiceRegistry(
        selfNode,
        "worker-self",
        localRegistry,
        catalog,
        owner -> List.of(export),
        message -> {},
        Greeter.class.getClassLoader(),
        4,
        0.5,
        Duration.ofMillis(200),
        selfTenantId);
  }

  private InetSocketAddress startBackend(Greeter impl) throws IOException {
    SimpleServiceRegistry backing = new SimpleServiceRegistry();
    backing.register(OWNER, Greeter.class, impl);
    FabricServer server = new FabricServer(backing, Greeter.class.getClassLoader());
    servers.add(server);
    return (InetSocketAddress) server.listen(new InetSocketAddress("127.0.0.1", 0));
  }

  @Test
  @Timeout(10)
  void a_same_worker_registration_is_invoked_directly_by_name() throws Throwable {
    SimpleServiceRegistry localRegistry = new SimpleServiceRegistry();
    localRegistry.register(OWNER, Greeter.class, name -> "same-worker:" + name);
    FabricServiceRegistry registry = newRegistry(localRegistry, new ServiceCatalog());

    Optional<Object> result =
        registry.invokeByName(
            Greeter.class.getName(),
            1,
            "greet",
            new String[] {"java.lang.String"},
            new Object[] {"x"});

    assertEquals(Optional.of("same-worker:x"), result);
  }

  @Test
  @Timeout(10)
  void a_same_machine_registration_is_invoked_over_the_wire_by_name() throws Throwable {
    InetSocketAddress sameMachineAddress = startBackend(name -> "same-machine:" + name);
    ServiceCatalog catalog = new ServiceCatalog();
    catalog.localRegister(
        selfNode, "worker-other", OWNER, GREETER_EXPORT, Optional.empty(), sameMachineAddress);
    FabricServiceRegistry registry = newRegistry(new SimpleServiceRegistry(), catalog);

    Optional<Object> result =
        registry.invokeByName(
            Greeter.class.getName(),
            1,
            "greet",
            new String[] {"java.lang.String"},
            new Object[] {"x"});

    assertEquals(Optional.of("same-machine:x"), result);
  }

  @Test
  @Timeout(10)
  void a_remote_registration_is_invoked_over_the_wire_by_name() throws Throwable {
    InetSocketAddress remoteAddress = startBackend(name -> "remote:" + name);
    ServiceCatalog catalog = new ServiceCatalog();
    catalog.localRegister(
        new MemberId("node-b", new InetSocketAddress("127.0.0.1", 7947)),
        "worker-b",
        OWNER,
        GREETER_EXPORT,
        Optional.empty(),
        remoteAddress);
    FabricServiceRegistry registry = newRegistry(new SimpleServiceRegistry(), catalog);

    Optional<Object> result =
        registry.invokeByName(
            Greeter.class.getName(),
            1,
            "greet",
            new String[] {"java.lang.String"},
            new Object[] {"x"});

    assertEquals(Optional.of("remote:x"), result);
  }

  @Test
  @Timeout(10)
  void an_unknown_interface_name_returns_empty_rather_than_throwing() throws Throwable {
    FabricServiceRegistry registry = newRegistry(new SimpleServiceRegistry(), new ServiceCatalog());

    Optional<Object> result =
        registry.invokeByName(
            "com.gimle.example.NoSuchInterface", 1, "whatever", new String[0], new Object[0]);

    assertEquals(Optional.empty(), result);
  }

  @Test
  @Timeout(10)
  void a_version_with_no_matching_exporter_returns_empty() throws Throwable {
    InetSocketAddress remoteAddress = startBackend(name -> "remote:" + name);
    ServiceCatalog catalog = new ServiceCatalog();
    catalog.localRegister(
        new MemberId("node-b", new InetSocketAddress("127.0.0.1", 7947)),
        "worker-b",
        OWNER,
        GREETER_EXPORT, // major version 1
        Optional.empty(),
        remoteAddress);
    FabricServiceRegistry registry = newRegistry(new SimpleServiceRegistry(), catalog);

    Optional<Object> result =
        registry.invokeByName(
            Greeter.class.getName(),
            2,
            "greet",
            new String[] {"java.lang.String"},
            new Object[] {"x"});

    assertEquals(Optional.empty(), result);
  }

  @Test
  @Timeout(10)
  void a_wrong_method_name_fails_clearly_rather_than_hanging_or_matching_a_wrong_overload() {
    SimpleServiceRegistry localRegistry = new SimpleServiceRegistry();
    localRegistry.register(OWNER, Greeter.class, name -> "same-worker:" + name);
    FabricServiceRegistry registry = newRegistry(localRegistry, new ServiceCatalog());

    assertThrows(
        NoSuchMethodException.class,
        () ->
            registry.invokeByName(
                Greeter.class.getName(),
                1,
                "notARealMethod",
                new String[] {"java.lang.String"},
                new Object[] {"x"}));
  }

  @Test
  @Timeout(10)
  void wrong_param_type_names_fail_clearly_rather_than_hanging_or_matching_a_wrong_overload() {
    SimpleServiceRegistry localRegistry = new SimpleServiceRegistry();
    localRegistry.register(OWNER, Greeter.class, name -> "same-worker:" + name);
    FabricServiceRegistry registry = newRegistry(localRegistry, new ServiceCatalog());

    assertThrows(
        NoSuchMethodException.class,
        () ->
            registry.invokeByName(
                Greeter.class.getName(), 1, "greet", new String[] {"int"}, new Object[] {1}));
  }

  @Test
  @Timeout(10)
  void a_remote_wrong_method_name_fails_clearly_too() throws IOException {
    InetSocketAddress remoteAddress = startBackend(name -> "remote:" + name);
    ServiceCatalog catalog = new ServiceCatalog();
    catalog.localRegister(
        new MemberId("node-b", new InetSocketAddress("127.0.0.1", 7947)),
        "worker-b",
        OWNER,
        GREETER_EXPORT,
        Optional.empty(),
        remoteAddress);
    FabricServiceRegistry registry = newRegistry(new SimpleServiceRegistry(), catalog);

    assertThrows(
        NoSuchMethodException.class,
        () ->
            registry.invokeByName(
                Greeter.class.getName(),
                1,
                "notARealMethod",
                new String[] {"java.lang.String"},
                new Object[] {"x"}));
  }

  @Test
  @Timeout(10)
  void a_same_worker_application_exception_is_rethrown_with_its_real_type() {
    SimpleServiceRegistry localRegistry = new SimpleServiceRegistry();
    localRegistry.register(
        OWNER,
        Greeter.class,
        name -> {
          throw new IllegalStateException("boom: " + name);
        });
    FabricServiceRegistry registry = newRegistry(localRegistry, new ServiceCatalog());

    IllegalStateException thrown =
        assertThrows(
            IllegalStateException.class,
            () ->
                registry.invokeByName(
                    Greeter.class.getName(),
                    1,
                    "greet",
                    new String[] {"java.lang.String"},
                    new Object[] {"x"}));
    assertTrue(thrown.getMessage().contains("boom: x"));
  }

  @Test
  @Timeout(10)
  void a_remote_application_exception_is_rethrown_with_its_real_type() throws IOException {
    InetSocketAddress address =
        startBackend(
            name -> {
              throw new IllegalStateException("boom: " + name);
            });
    ServiceCatalog catalog = new ServiceCatalog();
    catalog.localRegister(
        selfNode, "worker-throwing", OWNER, GREETER_EXPORT, Optional.empty(), address);
    FabricServiceRegistry registry = newRegistry(new SimpleServiceRegistry(), catalog);

    RuntimeException thrown =
        assertThrows(
            RuntimeException.class,
            () ->
                registry.invokeByName(
                    Greeter.class.getName(),
                    1,
                    "greet",
                    new String[] {"java.lang.String"},
                    new Object[] {"x"}));
    assertTrue(thrown.getMessage().contains("boom: x"));
  }

  // ---- cross-tenant deny: this new path must go through the same tenant check lookup(Class)
  // enforces, not bypass it ----

  @Test
  @Timeout(10)
  void a_caller_from_a_different_tenant_cannot_invoke_a_restricted_export_by_name()
      throws IOException, Throwable {
    ServiceExport restricted =
        new ServiceExport(
            Greeter.class.getName(), Version.parse("1.0.0"), Optional.of(Set.of("tenant-a")));
    InetSocketAddress remoteAddress = startBackend(name -> "remote:" + name);
    ServiceCatalog catalog = new ServiceCatalog();
    catalog.localRegister(
        new MemberId("node-b", new InetSocketAddress("127.0.0.1", 7947)),
        "worker-b",
        OWNER,
        restricted,
        Optional.empty(),
        remoteAddress);
    FabricServiceRegistry registry =
        newRegistryForTenant(
            new SimpleServiceRegistry(), catalog, restricted, Optional.of("tenant-b"));

    Optional<Object> result =
        registry.invokeByName(
            Greeter.class.getName(),
            1,
            "greet",
            new String[] {"java.lang.String"},
            new Object[] {"x"});

    assertEquals(Optional.empty(), result);
  }

  @Test
  @Timeout(10)
  void an_allowed_tenant_can_invoke_a_restricted_export_by_name() throws IOException, Throwable {
    ServiceExport restricted =
        new ServiceExport(
            Greeter.class.getName(), Version.parse("1.0.0"), Optional.of(Set.of("tenant-a")));
    InetSocketAddress remoteAddress = startBackend(name -> "remote:" + name);
    ServiceCatalog catalog = new ServiceCatalog();
    catalog.localRegister(
        new MemberId("node-b", new InetSocketAddress("127.0.0.1", 7947)),
        "worker-b",
        OWNER,
        restricted,
        Optional.empty(),
        remoteAddress);
    FabricServiceRegistry registry =
        newRegistryForTenant(
            new SimpleServiceRegistry(), catalog, restricted, Optional.of("tenant-a"));

    Optional<Object> result =
        registry.invokeByName(
            Greeter.class.getName(),
            1,
            "greet",
            new String[] {"java.lang.String"},
            new Object[] {"x"});

    assertEquals(Optional.of("remote:x"), result);
  }
}
