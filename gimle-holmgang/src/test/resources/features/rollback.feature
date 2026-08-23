@holmgang @rollback
Feature: Rolling back a deployment restores an earlier module version
  A deployment rolled back to its previous revision converges every instance
  back to that revision's module version, recorded as a brand-new revision --
  never rewriting history -- through the exact same rolling-update convergence
  mechanism a fresh apply already uses.

  Scenario: Roll back to the revision before a rollout
    Given a running cluster from topology "ha-plaintext"
    And module "greeter-provider" version "1.0.0" deployed with 2 replicas as "rollback-greeter"
    And a guard that "rollback-greeter" keeps at least 1 ACTIVE instance
    And "rollback-greeter" is rolled to a rebuilt provider version "1.1.0" with maxUnavailable 1 and maxSurge 1
    And within 180s all 2 instances of "rollback-greeter" are ACTIVE on version "1.1.0"
    When "rollback-greeter" is rolled back to the previous revision
    Then within 180s all 2 instances of "rollback-greeter" are ACTIVE on version "1.0.0"
    And the guard held
