@holmgang @networking
Feature: NetworkPolicySpec cluster-wide durability and guarded edits
  NetworkPolicyRegistry persists through gimle-mimir, the same shape ServiceSpec's own registry
  already has -- not an in-memory map private to whichever control-plane replica happened to handle
  the write. A NetworkPolicySpec created against one replica must be readable through every other
  replica sharing that same store. Writes carry a version guard, so a second operator editing the
  same policy against a stale version is refused rather than silently overwriting the first, and a
  policy naming a tenant that does not exist is refused at admission rather than stored as a rule
  that can never match anything.

  @network-policy-coverage
  Scenario: A network policy created through one control-plane replica is visible through another
    Given a running cluster from topology "ha-plaintext"
    And a tenant "some-other-tenant" with quota 1073741824 bytes memory, 2000 millicores and 5 instances
    When a network policy "replica-visibility-policy" for tenant "holmgang-tenant" allowing caller tenant "some-other-tenant" is created via control-plane replica 0
    Then within 30s network policy "replica-visibility-policy" is visible via control-plane replica 1
    And network policy "replica-visibility-policy" for tenant "holmgang-tenant" via control-plane replica 1 allows caller tenant "some-other-tenant"

  @network-policy-coverage
  Scenario: A concurrent edit against a stale version is refused instead of silently winning
    Given a running cluster from topology "ha-plaintext"
    And a tenant "partner-one" with quota 1073741824 bytes memory, 2000 millicores and 5 instances
    And a tenant "partner-two" with quota 1073741824 bytes memory, 2000 millicores and 5 instances
    When a network policy "guarded-policy" for tenant "holmgang-tenant" allowing caller tenant "partner-one" is created via control-plane replica 0
    And network policy "guarded-policy" for tenant "holmgang-tenant" adds caller tenant "partner-two" via control-plane replica 0
    Then a second edit of network policy "guarded-policy" for tenant "holmgang-tenant" against the version it started from is rejected with status 409
    And network policy "guarded-policy" for tenant "holmgang-tenant" via control-plane replica 1 allows caller tenant "partner-one"
    And network policy "guarded-policy" for tenant "holmgang-tenant" via control-plane replica 1 allows caller tenant "partner-two"

  @network-policy-coverage
  Scenario: A policy naming a tenant that does not exist is refused
    Given a running cluster from topology "ha-plaintext"
    Then creating network policy "typo-policy" for tenant "holmgang-tenant" allowing caller tenant "no-such-tenant" via control-plane replica 0 is rejected with status 400
