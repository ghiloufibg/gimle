@holmgang @secrets-pki-coverage
Feature: Secrets vault and PKI bootstrap
  Fafnir's versioned secret store round-trips values with real AES-256-GCM encryption, survives
  a master-key rotation without losing anything already written, tells a soft delete apart from a
  destroying one, still decrypts a value written before it ever had key ids at all, and enforces
  its own authorization independently of whatever the control plane already decided. The cluster's
  PKI bootstrap protocol signs a node's own CSR while stamping its privilege group server-side,
  carries the requested Subject Alternative Names through, rejects a CSR whose signature doesn't
  match its own declared key, and lets a node rotate its own certificate over its current mTLS
  connection without losing its identity.

  Scenario: A secret's versions round-trip and a soft delete behaves differently from a hard one
    Given a running cluster from topology "ha-plaintext"
    And secret "db-password" = "v1-value" for tenant "holmgang-tenant"
    And secret "db-password" = "v2-value" for tenant "holmgang-tenant"
    Then secret "db-password" for tenant "holmgang-tenant" has versions "1,2"
    And the latest value of secret "db-password" for tenant "holmgang-tenant" is "v2-value"
    And version 1 of secret "db-password" for tenant "holmgang-tenant" is "v1-value"
    When secret "db-password" for tenant "holmgang-tenant" is soft deleted
    Then the latest value of secret "db-password" for tenant "holmgang-tenant" is absent
    But version 1 of secret "db-password" for tenant "holmgang-tenant" is "v1-value"
    When secret "db-password" for tenant "holmgang-tenant" is hard deleted
    Then version 1 of secret "db-password" for tenant "holmgang-tenant" is absent

  Scenario: Rotating the secrets key re-encrypts an existing secret under the new key id
    Given a running cluster from topology "ha-plaintext"
    And secret "api-token" = "rotate-me" for tenant "holmgang-tenant"
    Then secret "api-token" for tenant "holmgang-tenant" is stored under key id 0
    When the secrets key is rotated
    Then secret "api-token" for tenant "holmgang-tenant" is now stored under the newly rotated key id
    And the latest value of secret "api-token" for tenant "holmgang-tenant" is "rotate-me"
    When the secrets key is rotated
    Then secret "api-token" for tenant "holmgang-tenant" is now stored under the newly rotated key id
    And the latest value of secret "api-token" for tenant "holmgang-tenant" is "rotate-me"
    And the last two secrets key rotations produced different active key ids

  Scenario: A legacy pre-key-id secret ciphertext still decrypts correctly
    Given a running cluster from topology "ha-plaintext"
    And a legacy-format secret "old-token" = "legacy-value" is planted for tenant "holmgang-tenant"
    Then the latest value of secret "old-token" for tenant "holmgang-tenant" is "legacy-value"

  Scenario: Fafnir's console session login round-trips and the plaintext session falls back to anonymous
    Given a running cluster from topology "minimal"
    Then the Fafnir console session is anonymous
    When an operator logs in to the Fafnir console as "holmgang-operator" with password "holmgang-operator-password"
    Then the Fafnir console login succeeds
    And the Fafnir console session reports "holmgang-operator" as logged in
    When the operator logs out of the Fafnir console
    Then the Fafnir console session is anonymous

  Scenario: A node-join CSR that self-declares a privileged group is stamped with the node group instead
    Given a running cluster from topology "mtls"
    When a NODE_CLIENT CSR self-declaring organization "gimle:operators" and common name "rogue-node" is submitted with a fresh bootstrap token
    Then the issued certificate organization is "gimle:nodes", not "gimle:operators"
    And the issued certificate lists "localhost" as a subject alternative name

  Scenario: A CSR whose signature does not match its own declared key is rejected
    Given a running cluster from topology "mtls"
    When a CSR with a mismatched signature is submitted as a NODE_CLIENT request with a fresh bootstrap token
    Then the CSR submission is rejected with status 400

  Scenario: A node rotates its own certificate over mTLS and keeps its identity
    Given a running cluster from topology "mtls"
    When node "node-1" renews its own certificate over mTLS via the CLI
    Then the renewed certificate for node "node-1" keeps its original subject but changes serial number

  Scenario: Fafnir independently authorizes node-scoped secret reads by tenant assignment
    Given a running cluster from topology "mtls"
    And secret "node-secret" = "node-secret-value" for tenant "holmgang-tenant"
    And a tenant "mtls-unassigned-tenant" with quota 268435456 bytes memory, 1000 millicores and 10 instances
    And secret "other-secret" = "other-value" for tenant "mtls-unassigned-tenant"
    And module "greeter-provider" version "1.0.0" deployed with 1 replica as "mtls-node-scope-provider" for tenant "holmgang-tenant"
    Then node "node-1" reading secret "node-secret" for tenant "holmgang-tenant" directly from Fafnir succeeds with value "node-secret-value"
    And node "node-1" reading secret "other-secret" for tenant "mtls-unassigned-tenant" directly from Fafnir is denied
