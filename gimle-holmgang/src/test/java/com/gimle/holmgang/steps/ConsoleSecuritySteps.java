package com.gimle.holmgang.steps;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.holmgang.HolmgangException;
import com.gimle.holmgang.cluster.ClusterApi;
import com.gimle.holmgang.topology.QuotaSpec;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Steps driving the console's own login/session/RBAC surface -- {@code /auth/login}, {@code
 * /auth/session}, {@code /roles}, {@code /rolebindings} -- which only ever enforces anything under
 * mTLS ({@code ApiServer#requireAuthorized} short-circuits to "always allowed" over plaintext), so
 * every scenario using these steps runs against the "mtls" topology. Logging in presents no client
 * certificate at all -- the same posture {@code SecuritySteps}' anonymous-write scenario already
 * establishes -- since a username/password login is inherently the credential a browser session
 * uses instead of one.
 */
public final class ConsoleSecuritySteps {

  private final ScenarioWorld world;

  public ConsoleSecuritySteps(final ScenarioWorld world) {
    this.world = world;
  }

  @Given("a console account {string} with password {string} exists")
  public void aConsoleAccountWithPasswordExists(final String username, final String password) {
    world.cluster().api().putAccount(username, password);
    // putAccount's own 200 only means the write was accepted by the store leader, not that a
    // read against this replica's own state machine is guaranteed to observe it yet -- a real
    // login attempt immediately afterward can otherwise race that apply and see "no such
    // account," an entirely different failure than the one this step's callers are testing for.
    world
        .cluster()
        .when()
        .probe(
            "account " + username + " is readable",
            () -> world.cluster().api().accountExists(username))
        .await(Duration.ofSeconds(10));
  }

  @Given("role {string} grants {string} on {string} scoped to tenant {string}")
  public void roleGrantsOnScopedToTenant(
      final String roleName, final String verb, final String resource, final String tenantId) {
    world
        .cluster()
        .api()
        .putRole(
            roleName,
            List.of(new ClusterApi.PermissionSpec(resource, verb, Optional.of(tenantId))));
  }

  @Given("role binding {string} binds user {string} to role {string}")
  public void roleBindingBindsUserToRole(
      final String bindingId, final String username, final String roleName) {
    world.cluster().api().putRoleBinding(bindingId, "user:" + username, roleName);
  }

  /**
   * Address-keyed login throttling (see {@code ApiServer#handleAuthLogin}) is shared, in-memory,
   * cluster-lifetime state keyed only by remote address, not by username -- every scenario using
   * these steps logs in from the same loopback address, so a deliberately-throttled scenario (the
   * repeated-wrong-password one) can leave a later scenario's own, otherwise-legitimate login
   * address-throttled too. Polls (Heimdall's own condition mechanism, not a blind sleep) until a
   * fresh attempt clears 429, then returns that attempt's real outcome (200 or 401) unmodified --
   * this never masks a genuine credential failure, since a bad password fails with 401, not 429.
   */
  @When("{string} logs in with password {string}")
  public void logsInWithPassword(final String username, final String password) {
    final ClusterApi.LoginResult[] last = new ClusterApi.LoginResult[1];
    world
        .cluster()
        .when()
        .probe(
            "login for " + username + " is not address-throttled",
            () -> {
              last[0] = anonymousApi().login(username, password);
              return last[0].status() != 429;
            })
        .await(Duration.ofSeconds(30));
    world.lastLogin = last[0];
  }

  @When("{string} attempts {int} logins with the wrong password")
  public void attemptsLoginsWithTheWrongPassword(final String username, final int attempts) {
    final ClusterApi api = anonymousApi();
    for (int i = 0; i < attempts; i++) {
      world.lastLogin = api.login(username, "definitely-wrong-password-" + i);
    }
  }

  @Then("the login succeeds and the session resolves to {string}")
  public void theLoginSucceedsAndTheSessionResolvesTo(final String username) {
    assertEquals(200, requireLastLogin().status());
    final String cookie =
        requireLastLogin()
            .sessionCookie()
            .orElseThrow(
                () -> new HolmgangException("a successful login carried no session cookie"));
    assertEquals(Optional.of(username), anonymousApi().sessionUsername(cookie));
  }

  @Then("the login is rejected with status {int}")
  public void theLoginIsRejectedWithStatus(final int expectedStatus) {
    assertEquals(expectedStatus, requireLastLogin().status());
  }

  @Then("the last login attempt is throttled with status {int}")
  public void theLastLoginAttemptIsThrottledWithStatus(final int expectedStatus) {
    assertEquals(expectedStatus, requireLastLogin().status());
    assertTrue(
        requireLastLogin().retryAfterSeconds().isPresent(),
        "a throttled login response carried no Retry-After header");
  }

  @Then("{string} can write tenant {string}")
  public void canWriteTenant(final String username, final String tenantId) {
    assertEquals(200, sessionWriteTenant(tenantId));
    // Written through a session cookie, not world.cluster().api()'s own putTenant -- track it here
    // so the pooled-cluster cleanup hook (which only knows about world.tenants) still deletes it.
    world.tenants.add(tenantId);
  }

  @Then("{string} cannot write tenant {string}")
  public void cannotWriteTenant(final String username, final String tenantId) {
    assertEquals(403, sessionWriteTenant(tenantId));
  }

  @Then("within {int}s the audit trail records an allowed WRITE on TENANT for tenant {string}")
  public void theAuditTrailRecordsAnAllowedWriteOnTenantForTenant(
      final int seconds, final String tenantId) {
    world
        .cluster()
        .when()
        .probe(
            "the audit trail records an allowed WRITE on TENANT for tenant " + tenantId,
            () -> auditRecordsAllowedWrite(tenantId))
        .await(Duration.ofSeconds(seconds));
  }

  private boolean auditRecordsAllowedWrite(final String tenantId) {
    for (final Map<String, Object> event : world.cluster().api().auditEvents("TENANT", tenantId)) {
      if ("WRITE".equals(event.get("verb")) && Boolean.TRUE.equals(event.get("allowed"))) {
        return true;
      }
    }
    return false;
  }

  private int sessionWriteTenant(final String tenantId) {
    final String cookie =
        requireLastLogin()
            .sessionCookie()
            .orElseThrow(() -> new HolmgangException("no session cookie to write a tenant with"));
    return anonymousApi()
        .tryPutTenantAsSession(cookie, tenantId, QuotaSpec.of(1024L * 1024, 100, 1));
  }

  private ClusterApi.LoginResult requireLastLogin() {
    if (world.lastLogin == null) {
      throw new HolmgangException("no login was attempted in this scenario");
    }
    return world.lastLogin;
  }

  /**
   * No client certificate presented, the same client {@code SecuritySteps}' anonymous-write
   * scenario already uses -- a console session is authenticated by its cookie, never by mTLS, so
   * every step here (including the ones re-using an already-issued cookie) goes through this same
   * identity-less transport.
   */
  private ClusterApi anonymousApi() {
    return world.cluster().anonymousApi(0);
  }
}
