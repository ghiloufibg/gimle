@holmgang @partition @destructive
Feature: Partition tolerance
  Control planes are stateless: a replica cut off from the store cluster stops
  serving instead of serving stale garbage, the surviving replica carries all
  work meanwhile, and the level-triggered reconcilers converge the moment the
  partition heals.

  Scenario: A control plane cut off from the store stops serving and reconverges after heal
    Given a running cluster from topology "ha-proxied"
    When the network between control plane 1 and all stores is cut
    Then within 30s control plane 1 stops serving
    And module "greeter-provider" version "1.0.0" deployed with 1 replica as "partition-greeter"
    And within 60s deployment "partition-greeter" is ACTIVE observed via control-plane replica 0
    When the partition heals
    Then within 60s deployment "partition-greeter" is ACTIVE observed via control-plane replica 1

  Scenario: A store leader silently partitioned from its peers steps down and writes stay bounded
    Given a running cluster from topology "ha-proxied"
    When the store leader is partitioned from its peers
    Then within 10s the isolated store leader steps down
    When a tenant write is submitted against the cluster
    Then the submitted write completes within 30s instead of hanging
    When the partition heals
    Then within 60s the cluster accepts writes again
