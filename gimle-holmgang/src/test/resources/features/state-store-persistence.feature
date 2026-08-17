@holmgang @state-store @destructive
Feature: State store persistence across a restart
  Every resource kind the store holds -- tenants, RBAC roles/bindings/accounts, and a
  log compacted past its own snapshot threshold -- survives a real process restart
  against the same on-disk directory: the store rebuilds its state from disk (and,
  once compaction has run, from its own persisted snapshot) rather than only ever
  replaying an in-memory log.

  @raft-store-coverage
  Scenario: Tenants, roles, role bindings, and accounts survive a store restart, snapshot included
    Given a running cluster from topology "minimal"
    And a tenant "persist-tenant" with quota 1048576 bytes memory, 100 millicores and 5 instances
    When role "persist-role" granting "TENANT" "READ" is created
    And role binding "persist-binding" binds subject "user:persist-user" to role "persist-role"
    And account "persist-user" with password "persist-password" is created
    And 10500 tenants are written directly to the store
    And store 0 is restarted
    Then within 60s the cluster accepts writes again
    And within 30s tenant "persist-tenant" is readable
    And within 30s tenant "bulk-compaction-0" is readable
    And within 30s tenant "bulk-compaction-10499" is readable
    And role "persist-role" is readable
    And role binding "persist-binding" is readable
    And account "persist-user" is readable
