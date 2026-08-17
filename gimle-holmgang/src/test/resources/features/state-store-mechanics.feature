@holmgang @state-store
Feature: State store mechanics
  Leader-local leases and node heartbeats sit deliberately outside the replicated Raft
  log, the per-instance event log is capped and newest-first, and a StatefulSet's
  ordinal index stays bound to the node it first lands on -- all real StateStore
  mechanisms, each observable through the real control-plane API or the real
  StoreClient production client against a live cluster.

  @raft-store-coverage
  Scenario: A lease is exclusive to its holder until it expires
    Given a running cluster from topology "ha-plaintext"
    When holder "holder-a" acquires lease "mechanics-lease" for 3s
    Then the lease acquisition succeeds
    When holder "holder-b" tries to acquire lease "mechanics-lease" for 3s
    Then the lease acquisition is denied
    Then within 15s holder "holder-b" acquires lease "mechanics-lease" for 3s

  @raft-store-coverage
  Scenario: Node heartbeats update continuously for a live node
    Given a running cluster from topology "ha-plaintext"
    Then within 30s node "node-1" has reported a heartbeat
    And within 30s node "node-1"'s heartbeat advances

  @raft-store-coverage
  Scenario: An instance's event log is capped and returns newest first
    Given a running cluster from topology "ha-plaintext"
    And module "greeter-provider" version "1.0.0" deployed with 1 replica as "event-log-greeter"
    When 55 synthetic lifecycle events are relayed for instance 0 of "event-log-greeter" on node "node-1"
    Then instance 0 of "event-log-greeter" has exactly 50 recorded events, newest first

  @raft-store-coverage
  Scenario: A StatefulSet ordinal index stays bound to the node it first lands on
    Given a running cluster from topology "ha-plaintext"
    And statefulset "sticky-set" with 1 replica of provider "greeter-provider" version "1.0.0" is deployed
    Then within 120s statefulset "sticky-set" index 0 is ACTIVE
    And the node currently hosting statefulset "sticky-set" index 0 is remembered
    When the worker hosting instance 0 of "sticky-set" is killed
    Then within 60s instance 0 of "sticky-set" is hosted by a new worker
    And within 60s statefulset "sticky-set" index 0 is ACTIVE
    And statefulset "sticky-set" index 0 is rebound to the same node
