@holmgang @self-healing
Feature: Tiered self-healing
  Worker-tier failure is repaired by respawning the worker JVM; a module that can
  never pass its own liveness probe exhausts its restart budget and is escalated
  to a terminal FAILED instead of restarting forever.

  Scenario: A killed worker JVM is respawned and the deployment returns to ACTIVE
    Given a running cluster from topology "minimal"
    And module "greeter-provider" version "1.0.0" deployed with 1 replica as "healing-greeter"
    When the worker hosting instance 0 of "healing-greeter" is killed
    # Recovery is proven at the process level: the respawn is faster than the observed
    # control-plane state can be relied on to ever show a non-ACTIVE view.
    Then within 60s instance 0 of "healing-greeter" is hosted by a new worker
    And within 120s deployment "healing-greeter" is ACTIVE

  Scenario: A module that never passes liveness is escalated to FAILED for good
    Given a running cluster from topology "minimal"
    When a provider that always fails liveness is deployed as "doomed-greeter"
    Then within 240s deployment "doomed-greeter" has a FAILED instance
