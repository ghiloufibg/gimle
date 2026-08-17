@holmgang @mtls
Feature: Mutual TLS operation
  The whole cluster runs with transport security on: every inter-process hop is
  mutually authenticated TLS, the node agent bootstraps its own certificate
  through the real CSR flow gated by a bootstrap token, and a client without an
  identity is turned away at authentication.

  Scenario: The cluster functions end to end over mutual TLS
    Given a running cluster from topology "mtls"
    And secret "some-secret-key" = "mtls-secret-value" for tenant "holmgang-tenant"
    And module "greeter-provider" version "1.0.0" deployed with 1 replica as "mtls-provider" for tenant "holmgang-tenant"
    Then within 60s instance 0 of "mtls-provider" logs "mtls-secret-value"

  Scenario: An anonymous client cannot write
    Given a running cluster from topology "mtls"
    Then an anonymous tenant write is rejected with status 401

  @raft-store-coverage
  Scenario: The audit trail records and filters real authorization decisions over mutual TLS
    Given a running cluster from topology "mtls"
    And a tenant "audit-tenant-a" with quota 1048576 bytes memory, 100 millicores and 5 instances
    And a tenant "audit-tenant-b" with quota 1048576 bytes memory, 100 millicores and 5 instances
    Then the audit trail for tenant "audit-tenant-a" contains a "TENANT" entry
    And the audit trail for tenant "audit-tenant-a" excludes tenant "audit-tenant-b"
