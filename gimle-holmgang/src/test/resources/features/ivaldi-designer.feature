@holmgang @ivaldi
Feature: Cluster designer real-cluster runs
  Ivaldi is Gimlé's cluster designer: a standalone local process that stores Blueprint documents,
  validates their rendered topology.yaml/bundle.yaml/manifest output against the real Hilmir/Mimir
  parsers, and -- given a saved cluster connection -- actually runs a Blueprint against it, booting
  a real platform process tree and deploying a real module onto it, all driven purely over HTTP the
  way its own web console is. gimle-smoke-tests' own IvaldiRunEngineIT already proves the real-boot
  path end to end as a plain JUnit test; these scenarios prove the same designer-to-live-cluster
  path through Holmgang's own Gherkin coverage.

  Scenario: A saved blueprint document is read back reliably
    Given a running Ivaldi process
    When a blueprint document named "orders-platform-local" is saved
    Then reading that blueprint back returns a document named "orders-platform-local"
    And reading it back a second time returns the exact same content

  Scenario: Tier-2 validation flags a topology with no agents, the same way hilmir validate would
    Given a running Ivaldi process
    When a topology declaring no agents is submitted for validation
    Then the validation response includes a "NO_AGENTS" finding naming "topology.yaml"

  Scenario: A blueprint run through Ivaldi boots a real cluster, deploys a module, and tears down
    Given a running Ivaldi process
    And a saved blueprint deploying "hello-module" as "hello-deployment"
    And a saved cluster connection pointing at a fresh single-machine topology
    When the blueprint is run against that cluster
    Then within 120s the run reaches "running"
    And within 60s deployment "hello-deployment" is ACTIVE on that cluster
    When the run is stopped
    Then within 30s the run is idle
    And the cluster's process tree is torn down
