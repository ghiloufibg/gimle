@holmgang @module-lifecycle-coverage
Feature: Module system mechanics
  Dependency resolution honors declared version ranges, a same-worker service registry
  answers name-driven calls and cuts over to a newer version once it's ready, a hook
  failure or a perpetually in-flight request are handled by the lifecycle state machine
  exactly as designed, and a manifest that fails its own validation never gets placed.

  # Both fixtures are TIER_1 on the single node of the "minimal" topology, so the node
  # agent's own density packing puts them on the same worker JVM -- the only way a
  # dependent's "requires:" wiring can ever find its dependency at all, since
  # ModuleResolver only looks inside its own worker's registry.
  Scenario: A dependent resolves only when a version inside its declared range is present
    Given a running cluster from topology "minimal"
    And the dependency library version "1.0.0" with greeting "v1" deployed as "dep-lib"
    When module "ok" requiring the dependency library in version range "[1.0.0,2.0.0)" is deployed as "dep-consumer-ok"
    Then within 60s deployment "dep-consumer-ok" is ACTIVE
    And within 60s instance 0 of "dep-consumer-ok" logs "hello world v1"
    When module "bad" requiring the dependency library in version range "[2.0.0,3.0.0)" is deployed as "dep-consumer-bad"
    Then within 60s deployment "dep-consumer-bad" has a FAILED instance

  # A fresh worker, so this scenario's two library versions and its own consumer never
  # collide with the dependency-resolution scenario's own occupants of the shared worker.
  Scenario: The service registry cuts a same-worker caller over to a newer ready version
    Given a running cluster from topology "minimal"
    And the dependency library version "1.0.0" with greeting "cutover-v1" deployed as "cutover-lib"
    When a service-cutover consumer is deployed as "cutover-consumer"
    Then within 60s instance 0 of "cutover-consumer" logs "cutover-v1"
    When the dependency library version "1.5.0" with greeting "cutover-v2" deployed as "cutover-lib-v2"
    Then within 60s instance 0 of "cutover-consumer" logs "cutover-v2"

  Scenario: A hook that always throws on start never reaches ACTIVE
    Given a running cluster from topology "minimal"
    And node "node-1" is registered
    When a module whose start hook always throws is deployed as "broken-start"
    Then within 60s deployment "broken-start" has a FAILED instance

  Scenario: Stopping a module with a perpetually in-flight request still completes
    Given a running cluster from topology "minimal"
    And a module with a perpetually in-flight request deployed as "stuck-request"
    When deployment "stuck-request" is deleted
    Then within 30s deployment "stuck-request" is absent

  Scenario: A manifest that fails its own validation never gets placed
    Given a running cluster from topology "minimal"
    When a module with a bogus isolation tier is deployed as "malformed-tier"
    Then deployment "malformed-tier" stays unplaced for 10s
    When a module whose resource request exceeds its own limit is deployed as "malformed-limits"
    Then deployment "malformed-limits" stays unplaced for 10s

  # initialDelaySeconds is deliberately kept under WorkerRuntime's own
  # DEFAULT_STABLE_UPTIME_THRESHOLD (10s): at or above that, the restart-tier stability
  # timer confirms the module "stable" before its very first liveness tick ever fires,
  # since that timer is armed off a fixed 10s window with no awareness of a longer
  # configured initial delay -- silently defeating crash-loop escalation forever for a
  # module declaring a long warm-up delay. A real platform gap, not exercised here.
  Scenario: A liveness probe's initial delay is honored before the first tick
    Given a running cluster from topology "minimal"
    When a provider that always fails liveness with a 5s initial delay is deployed as "delayed-doomed-greeter"
    Then within 60s deployment "delayed-doomed-greeter" is ACTIVE
    And deployment "delayed-doomed-greeter" keeps 1 ACTIVE instance for 3s
    Then within 240s deployment "delayed-doomed-greeter" has a FAILED instance
