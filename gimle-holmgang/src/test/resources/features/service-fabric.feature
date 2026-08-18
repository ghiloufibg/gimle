@holmgang @service-fabric
Feature: Service endpoint resolution for a hosted module
  A Service declared in front of a deployment resolves a live endpoint from a hosted
  (non-Vessel) module's own reported port -- the same runtime port-reporting mechanism
  that lets ServiceEndpointResolver resolve an ordinary fabric-hosted module instead of
  only ever a Vessel workload's allocated port.

  Scenario: A Service resolves a live endpoint for a hosted module reporting its own port
    Given a running cluster from topology "minimal"
    And a module reporting its own port is deployed as "port-reporting-provider"
    When a Service "greeter-svc" is declared fronting deployment "port-reporting-provider" on port 8080 targeting port 9500
    Then within 60s Service "greeter-svc" resolves a live endpoint
