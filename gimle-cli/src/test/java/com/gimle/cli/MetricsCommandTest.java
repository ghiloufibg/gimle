package com.gimle.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.core.module.ModuleId;
import com.gimle.core.module.Version;
import com.gimle.core.protocol.Json;
import com.gimle.mimir.manifest.DeploymentSpec;
import com.gimle.mimir.manifest.PlacementConstraints;
import com.gimle.mimir.raft.StateMutation;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceAccessMode;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

/**
 * {@code gimle metrics} against a real {@link com.gimle.controlplane.api.ApiServer}. The lock is a
 * read lock on the system properties every server here consults for its transport mode -- this
 * class never writes one, it only must not observe another class mid-change.
 */
@ResourceLock(value = Resources.SYSTEM_PROPERTIES, mode = ResourceAccessMode.READ)
class MetricsCommandTest {

  @TempDir(cleanup = CleanupMode.NEVER)
  Path tempDir;

  private InProcessCluster cluster;
  private ByteArrayOutputStream outBuffer;
  private ByteArrayOutputStream errBuffer;
  private PrintStream out;
  private PrintStream err;

  @BeforeEach
  void startCluster() {
    cluster = InProcessCluster.start(tempDir);
    outBuffer = new ByteArrayOutputStream();
    errBuffer = new ByteArrayOutputStream();
    out = new PrintStream(outBuffer, true, StandardCharsets.UTF_8);
    err = new PrintStream(errBuffer, true, StandardCharsets.UTF_8);
  }

  @AfterEach
  void stopCluster() {
    cluster.close();
  }

  private int run(String... args) {
    String[] withServer = new String[args.length + 2];
    System.arraycopy(args, 0, withServer, 0, args.length);
    withServer[args.length] = "--server";
    withServer[args.length + 1] = cluster.address();
    return GimleCli.run(withServer, out, err);
  }

  private String stdout() {
    return outBuffer.toString(StandardCharsets.UTF_8);
  }

  private String stderr() {
    return errBuffer.toString(StandardCharsets.UTF_8);
  }

  /**
   * An ordinary deployment, submitted the way an operator would: through the API. Its manifest
   * names no tenant, which the manifest parser resolves to the {@code default} tenant rather than
   * to the untenanted namespace.
   */
  private void apply(String name) throws IOException {
    Path file = tempDir.resolve(name + ".yaml");
    Files.writeString(
        file,
        """
        kind: Deployment
        name: %s
        module:
          name: com.gimle.example.orders
          version: 1.0.0
        artifactPath: /var/gimle/artifacts/orders-1.0.0.jar
        replicas: 1
        """
            .formatted(name));
    assertEquals(0, run("apply", "-f", file.toString()), stderr());
    outBuffer.reset();
  }

  /**
   * Proposed straight against the real store rather than submitted through the API: plaintext mode
   * deliberately refuses to admit a second real tenant (it has no caller identity to tell tenants
   * apart), and two tenants is precisely the shape needed here. The behavior under test belongs to
   * the read path, which stays entirely real.
   */
  private void storeTenantedDeployment(String tenantId, String name) {
    storeDeployment(Optional.of(tenantId), name);
  }

  /**
   * The untenanted namespace has no manifest spelling -- an omitted {@code tenantId} parses as
   * {@code default} -- so the only way to put a deployment there is straight through the store,
   * which is exactly what makes it worth asserting the rollup tells it apart from a real tenant.
   */
  private void storeUntenantedDeployment(String name) {
    storeDeployment(Optional.empty(), name);
  }

  private void storeDeployment(Optional<String> tenantId, String name) {
    cluster
        .storeClient()
        .propose(
            new StateMutation.PutDeployment(
                new DeploymentSpec(
                    name,
                    new ModuleId("com.gimle.example.orders", Version.parse("1.0.0")),
                    "/var/gimle/artifacts/orders-1.0.0.jar",
                    1,
                    PlacementConstraints.NONE,
                    Optional.empty(),
                    tenantId),
                0L));
  }

  private List<Map<String, Object>> rollupRows() {
    assertEquals(0, run("-o", "json", "metrics"), stderr());
    return Json.asObjectList(Json.parse(stdout()));
  }

  @Test
  void metrics_rolls_up_every_readable_deployment() throws Exception {
    apply("orders");
    apply("billing");

    assertEquals(0, run("metrics"), stderr());

    String table = stdout();
    assertTrue(table.contains("deploymentName"), table);
    assertTrue(table.contains("avgRequestRatePerSecond"), table);
    assertTrue(table.contains("orders"), table);
    assertTrue(table.contains("billing"), table);
  }

  @Test
  void a_deployment_no_instance_has_reported_for_contributes_a_zeroed_row_rather_than_none()
      throws Exception {
    apply("orders");

    List<Map<String, Object>> rows = rollupRows();

    assertEquals(1, rows.size(), stdout());
    assertEquals(0, ((Number) rows.get(0).get("instanceCount")).intValue());
  }

  @Test
  void a_rollup_with_no_deployments_at_all_reports_nothing_rather_than_failing() {
    assertEquals(0, run("metrics"), stderr());
    assertTrue(stdout().contains("No resources found."), stdout());
  }

  /**
   * Two tenants running a same-named deployment produce two rows. Each carries its own tenant id,
   * which is what makes them distinguishable -- both are kept, neither is merged into an average
   * the server never computed.
   */
  @Test
  void same_deployment_name_in_two_tenants_yields_two_rows_each_naming_its_own_tenant() {
    storeTenantedDeployment("acme", "api");
    storeTenantedDeployment("globex", "api");
    storeTenantedDeployment("acme", "billing");

    List<Map<String, Object>> rows = rollupRows();

    List<Object> apiTenants =
        rows.stream()
            .filter(row -> "api".equals(row.get("deploymentName")))
            .map(row -> row.get("tenantId"))
            .sorted(Comparator.comparing(String::valueOf))
            .toList();
    assertEquals(List.of("acme", "globex"), apiTenants, stdout());
    Map<String, Object> billing =
        rows.stream()
            .filter(row -> "billing".equals(row.get("deploymentName")))
            .findFirst()
            .orElseThrow();
    assertEquals("acme", billing.get("tenantId"), stdout());
  }

  @Test
  void the_table_output_carries_the_tenant_column_so_same_named_rows_read_apart() {
    storeTenantedDeployment("acme", "api");
    storeTenantedDeployment("globex", "api");

    assertEquals(0, run("metrics"), stderr());

    assertTrue(stdout().contains("tenantId"), stdout());
    assertTrue(stdout().contains("acme"), stdout());
    assertTrue(stdout().contains("globex"), stdout());
  }

  /**
   * An untenanted deployment is a real, distinct row -- not the same thing as an unknown tenant.
   */
  @Test
  void an_untenanted_deployment_is_told_apart_from_a_same_named_tenanted_one() {
    storeUntenantedDeployment("api");
    storeTenantedDeployment("acme", "api");

    List<Map<String, Object>> rows = rollupRows();

    List<Object> tenants =
        rows.stream()
            .filter(row -> "api".equals(row.get("deploymentName")))
            .map(row -> row.get("tenantId"))
            .toList();
    assertEquals(2, tenants.size(), stdout());
    assertTrue(tenants.contains("acme"), stdout());
    assertTrue(tenants.contains(null), stdout());
  }

  @Test
  void a_stray_positional_argument_is_rejected_with_the_verb_usage() {
    int exit = run("metrics", "orders");

    assertNotEquals(0, exit);
    assertTrue(stderr().contains("usage: gimle metrics"), stderr());
  }

  @Test
  void an_unreachable_control_plane_produces_a_clear_error_and_nonzero_exit() {
    int exit = GimleCli.run(new String[] {"metrics", "--server", "localhost:1"}, out, err);

    assertNotEquals(0, exit);
    assertTrue(stderr().contains("could not reach control plane"), stderr());
  }
}
