@holmgang @rolling-update
Feature: Rolling update preserves serving capacity
  A deployment updated to a new module version under a surge budget never drops
  below its guaranteed replica floor, and every instance ends up on the new
  version -- proven against a genuinely rebuilt v1.1.0 artifact, not a renamed jar.

  Scenario: Zero-downtime rollout under a surge budget
    Given a running cluster from topology "ha-plaintext"
    And module "greeter-provider" version "1.0.0" deployed with 2 replicas as "rolling-greeter"
    And a guard that "rolling-greeter" keeps at least 1 ACTIVE instance
    When "rolling-greeter" is rolled to a rebuilt provider version "1.1.0" with maxUnavailable 1 and maxSurge 1
    Then within 180s all 2 instances of "rolling-greeter" are ACTIVE on version "1.1.0"
    And the guard held
