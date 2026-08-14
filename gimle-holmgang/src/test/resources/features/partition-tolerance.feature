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
