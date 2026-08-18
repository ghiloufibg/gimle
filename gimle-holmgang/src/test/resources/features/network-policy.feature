@holmgang @networking
Feature: NetworkPolicySpec cluster-wide durability
  NetworkPolicyRegistry is a thin StoreReader/MutationSink facade over gimle-mimir, the same shape
  ServiceSpec's own registry already has -- not an in-memory map private to whichever
  control-plane replica happened to handle the write. A NetworkPolicySpec created against one
  replica must be readable through every other replica sharing that same store.

  @network-policy-coverage
  Scenario: A network policy created through one control-plane replica is visible through another
    Given a running cluster from topology "ha-plaintext"
    When a network policy "replica-visibility-policy" for tenant "holmgang-tenant" allowing caller tenant "some-other-tenant" is created via control-plane replica 0
    Then within 30s network policy "replica-visibility-policy" is visible via control-plane replica 1
    And network policy "replica-visibility-policy" for tenant "holmgang-tenant" via control-plane replica 1 allows caller tenant "some-other-tenant"
