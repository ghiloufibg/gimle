@holmgang @self-healing
Feature: Tiered self-healing
  Worker-tier failure is repaired by respawning the worker JVM; a module that can
  never pass its own liveness probe exhausts its restart budget and is escalated
  to a terminal FAILED instead of restarting forever.

  Scenario: A killed worker JVM is respawned and the deployment returns to ACTIVE
    Given a running cluster from topology "minimal"
    And module "greeter-provider" version "1.0.0" deployed with 1 replica as "healing-greeter"
    When the worker hosting instance 0 of "healing-greeter" is killed
    Then within 60s deployment "healing-greeter" is not fully ACTIVE
    And within 120s deployment "healing-greeter" is ACTIVE
    And instance 0 of "healing-greeter" is hosted by a different worker than before

  Scenario: A module that never passes liveness is escalated to FAILED for good
    Given a running cluster from topology "minimal"
    When a provider that always fails liveness is deployed as "doomed-greeter"
    Then within 240s deployment "doomed-greeter" has a FAILED instance
