@holmgang @module-lifecycle-coverage @mtls
Feature: Console login, sessions, and RBAC
  A console login round-trips a real PBKDF2-hashed password, issues a stateless
  HMAC-signed session cookie that a later request can be authenticated by, throttles
  repeated failures with an increasing backoff, and a role scoped to one tenant grants
  write access to that tenant alone -- all of it enforced only once the cluster runs
  with mutual TLS on, so every scenario here boots the "mtls" topology.

  Scenario: A console login round-trips the right password and rejects the wrong one
    Given a running cluster from topology "mtls"
    And a console account "sec-user" with password "correct-horse-battery-staple" exists
    When "sec-user" logs in with password "correct-horse-battery-staple"
    Then the login succeeds and the session resolves to "sec-user"
    When "sec-user" logs in with password "wrong-password"
    Then the login is rejected with status 401

  Scenario: Repeated failed logins are throttled with a Retry-After backoff
    Given a running cluster from topology "mtls"
    And a console account "throttle-user" with password "correct-horse-battery-staple" exists
    When "throttle-user" attempts 5 logins with the wrong password
    Then the last login attempt is throttled with status 429

  Scenario: A role scoped to one tenant grants write access to that tenant alone
    Given a running cluster from topology "mtls"
    And a console account "scoped-user" with password "scoped-pass-123" exists
    And role "rbac-tenant-writer" grants "WRITE" on "TENANT" scoped to tenant "rbac-tenant"
    And role binding "rbac-scoped-binding" binds user "scoped-user" to role "rbac-tenant-writer"
    When "scoped-user" logs in with password "scoped-pass-123"
    Then "scoped-user" can write tenant "rbac-tenant"
    And "scoped-user" cannot write tenant "rbac-other-tenant"
