@holmgang @raft @destructive
Feature: Store resilience under member loss
  The state store is a Raft cluster: killing one of its three members while a
  live write workload runs must cost no acknowledged write -- an acknowledged
  write is committed to a majority and survives any minority's death -- and the
  cluster must keep scheduling new work on the surviving quorum.

  Scenario: A store member dies mid-workload and nothing acknowledged is lost
    Given a running cluster from topology "ha-plaintext"
    And a background writer creating tenants every 200ms
    Then within 15s the writer has acknowledged at least 5 writes
    When store 0 is killed
    Then within 30s the cluster accepts writes again
    And module "greeter-provider" version "1.0.0" deployed with 1 replica as "post-kill-greeter"
    And every acknowledged tenant write is readable

  Scenario: The store leader dies mid-workload and nothing acknowledged is lost
    Given a running cluster from topology "ha-plaintext"
    And a background writer creating tenants every 200ms
    Then within 15s the writer has acknowledged at least 5 writes
    When the store leader is killed
    Then within 30s the cluster accepts writes again
    And within 30s the writer has acknowledged at least 20 writes
    And every acknowledged tenant write is readable

  @raft-store-coverage
  Scenario: A stale, partitioned follower cannot win an election despite outracing the cluster's term
    Given a running cluster from topology "ha-proxied"
    And a background writer creating tenants every 200ms
    Then within 15s the writer has acknowledged at least 5 writes
    When a non-leader store is partitioned from its peers
    Then within 20s the writer has acknowledged at least 60 writes
    When the partition heals
    Then within 15s the isolated store never wins the election that follows
    And within 30s the cluster accepts writes again

  @raft-store-coverage
  Scenario: A learner catches up through a compacted leader's snapshot and only helps quorum once promoted
    Given a running cluster from topology "ha-plaintext"
    When 10500 tenants are written directly to the store
    When a new store node joins the cluster
    Then within 60s the store reports 4 members
    When store 0 is killed
    And store 1 is killed
    Then the cluster does not accept writes for 5s
    When store 0 is restarted
    Then within 60s the cluster accepts writes again
    And within 30s tenant "bulk-compaction-0" is readable
    And within 30s tenant "bulk-compaction-10499" is readable
