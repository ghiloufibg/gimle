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
