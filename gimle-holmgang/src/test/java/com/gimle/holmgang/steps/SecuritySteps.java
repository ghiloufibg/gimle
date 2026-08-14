package com.gimle.holmgang.steps;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.gimle.holmgang.topology.QuotaSpec;
import io.cucumber.java.en.Then;

/**
 * Steps exercising the transport-security boundary from deliberately under-credentialed clients.
 */
public final class SecuritySteps {

  private final ScenarioWorld world;

  public SecuritySteps(final ScenarioWorld world) {
    this.world = world;
  }

  /**
   * A client that can complete the TLS handshake (it trusts the cluster CA) but presents no
   * identity at all -- the write must die at authentication, not at the transport.
   */
  @Then("an anonymous tenant write is rejected with status {int}")
  public void anAnonymousTenantWriteIsRejectedWithStatus(final int expectedStatus) {
    final int status =
        world
            .cluster()
            .anonymousApi(0)
            .tryPutTenant("anonymous-tenant", QuotaSpec.of(1024L * 1024, 100, 1));
    assertEquals(expectedStatus, status);
  }
}
