@holmgang @sleipnir
Feature: Sleipnir AOT cache safety under a directory classpath
  Holmgang boots every worker on the test JVM's own classpath, which mixes real jars with
  target/classes directories -- ineligible for a JDK AOT cache under JEP 483's own jars-only
  constraint. This proves Sleipnir degrades safely there rather than interfering with an ordinary
  deploy: the ineligibility is logged once, the deployment still reaches ACTIVE normally, and no
  cache files are ever written. Real cache-accelerated speedup is proven elsewhere
  (WorkerStartupBenchIT, against a real jars-only classpath) -- see gimle-holmgang/README.md.

  Scenario: the agent logs ineligibility and the deployment still reaches ACTIVE normally
    Given a running cluster from topology "minimal"
    And module "greeter-provider" version "1.0.0" deployed with 1 replica as "sleipnir-greeter"
    Then within 60s deployment "sleipnir-greeter" is ACTIVE
    And within 30s node "node-0" logs "AOT cache ineligible: directory on worker classpath"
    And node "node-0" has no AOT cache files
