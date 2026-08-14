@holmgang @lifecycle
Feature: Deployment lifecycle
  A module artifact submitted through the real HTTP API reaches ACTIVE on a real
  worker JVM, its tenant's secret is delivered end to end through Fafnir, and
  deleting the deployment drains it away completely.

  Scenario: A tenant-scoped module deploys, reads its secret, and is cleanly removed
    Given a running cluster from topology "minimal"
    And secret "some-secret-key" = "lifecycle-secret-value" for tenant "holmgang-tenant"
    And module "greeter-provider" version "1.0.0" deployed with 1 replica as "lifecycle-provider" for tenant "holmgang-tenant"
    Then within 60s instance 0 of "lifecycle-provider" logs "lifecycle-secret-value"
    When deployment "lifecycle-provider" is deleted
    Then within 60s deployment "lifecycle-provider" is absent

  # Same-node deliberately (the "minimal" topology has exactly one node): the scheduler is free
  # to split a provider/consumer pair across nodes, and cross-node service-export propagation has
  # no proven working path today -- a single node keeps the fabric call it proves deterministic.
  Scenario: A consumer completes a real fabric call to a provider
    Given a running cluster from topology "minimal"
    And module "greeter-provider" version "1.0.0" deployed with 1 replica as "fabric-provider"
    And module "greeter-consumer" version "1.0.0" deployed with 1 replica as "fabric-consumer"
    Then within 60s instance 0 of "fabric-consumer" logs "Hello, Gimlé!"

  Scenario: State written through one control-plane replica serves through another
    Given a running cluster from topology "ha-plaintext"
    And module "greeter-provider" version "1.0.0" deployed with 1 replica as "replicated-provider"
    Then within 120s deployment "replicated-provider" is ACTIVE observed via control-plane replica 1
