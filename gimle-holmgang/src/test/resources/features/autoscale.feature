# @destructive deliberately: a deleted provider's fabric endpoints outlive the deployment in the
# registry (locality-preferring selection then favors those same-machine dead endpoints over a
# live remote provider), so a load-and-scale cycle leaves the shared registry in a state
# deployment cleanup cannot restore for later Greeter consumers on a pooled cluster.
@holmgang @autoscale @destructive
Feature: Autoscaling under real load
  The request-rate signal the autoscaler reads is the provider's own
  worker-reported metric, driven here by genuine external HTTP load: every
  Gatling request to the deployed load generator becomes one real cross-worker
  fabric call to the provider. The CPU target is set deliberately unreachable,
  so a scale-up can only mean the request-rate signal drove it.

  Scenario: Request-rate load scales the provider up
    Given a running cluster from topology "ha-plaintext"
    And module "greeter-load-generator" version "1.0.0" deployed with 1 replica as "load-generator"
    And an autoscaling deployment "autoscale-greeter" of provider "1.0.0" with min 1 max 2 replicas targeting 5.0 requests per second
    When an open-model load of 20 requests per second runs for 60s against the load generator
    Then within 120s deployment "autoscale-greeter" has 2 ACTIVE replicas
