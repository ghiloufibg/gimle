@holmgang @chaos @destructive
Feature: Randomized fault soak
  kube-monkey's discipline, applied to a Gimle cluster: rather than one hand-aimed
  fault asserted once, Fenrir strikes repeatedly from a weighted palette -- worker
  kills, store and leader bounces, control-plane bounces -- over a soak window, and
  gates every next strike on full recovery from the last. A live write workload runs
  throughout, so the run proves both that the cluster reconverges after each fault and
  that no acknowledged write is ever lost across the whole storm.

  Scenario: The cluster survives a randomized fault soak with no lost writes
    Given a running cluster from topology "ha-plaintext"
    And module "greeter-provider" version "1.0.0" deployed with 1 replica as "chaos-greeter"
    And a background writer creating tenants every 200ms
    Then within 15s the writer has acknowledged at least 5 writes
    When Fenrir is unleashed for 60 seconds striking every 15 seconds
    Then the chaos ledger shows at least 3 executed faults
    And every executed fault recovered
    And within 30s the cluster accepts writes again
    And within 30s the writer has acknowledged at least 20 writes
    And every acknowledged tenant write is readable

  Scenario: The cluster survives a compound-fault soak with overlapping faults and no lost writes
    Given a running cluster from topology "ha-plaintext"
    And module "greeter-provider" version "1.0.0" deployed with 1 replica as "chaos-greeter"
    And a background writer creating tenants every 200ms
    Then within 15s the writer has acknowledged at least 5 writes
    When Fenrir is unleashed in compound-fault mode for 60 seconds striking every 8 seconds with a 5-second recovery gate
    Then the chaos ledger shows at least 4 executed faults
    And within 30s the cluster accepts writes again
    And within 30s the writer has acknowledged at least 20 writes
    And every acknowledged tenant write is readable
