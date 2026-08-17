@holmgang @manifests
Feature: Workload manifest parsing and validation
  Every workload kind's manifest is validated by ManifestParser's own kind dispatch and
  each kind-specific parser at submission time -- accepting a well-formed manifest and
  rejecting a malformed one with a clean 400, never a silent partial application.

  @raft-store-coverage
  Scenario: An unrecognized manifest kind is rejected via the dispatching parser
    Given a running cluster from topology "minimal"
    When a manifest with kind "Bogus" is submitted to "/deployments/bogus-kind"
    Then the submission is rejected with status 400

  @raft-store-coverage
  Scenario: A weighted autoscale policy is accepted, an unreplaceable disruption budget is rejected
    Given a running cluster from topology "minimal"
    When a deployment manifest with a weighted autoscale policy is submitted as "weighted-autoscale-dep"
    Then the manifest submission is accepted
    When a deployment manifest with maxUnavailable 0 and maxSurge 0 is submitted as "stuck-rollout-dep"
    Then the submission is rejected with status 400

  @raft-store-coverage
  Scenario: A DaemonSet rejects anti-affinity and nonzero surge but accepts zero surge
    Given a running cluster from topology "minimal"
    When a daemonset manifest requesting anti-affinity is submitted as "bad-affinity-ds"
    Then the submission is rejected with status 400
    When a daemonset manifest with maxSurge 1 is submitted as "bad-surge-ds"
    Then the submission is rejected with status 400
    When a daemonset manifest with maxSurge 0 is submitted as "good-ds"
    Then the manifest submission is accepted

  @raft-store-coverage
  Scenario: A CronJob applies sensible defaults and rejects an invalid schedule or concurrency policy
    Given a running cluster from topology "minimal"
    When a cronjob manifest with no explicit backoffLimit is submitted as "default-cronjob"
    Then the manifest submission is accepted
    When a cronjob manifest with schedule "not a schedule" is submitted as "bad-schedule-cronjob"
    Then the submission is rejected with status 400
    When a cronjob manifest with concurrencyPolicy "BOGUS" is submitted as "bad-policy-cronjob"
    Then the submission is rejected with status 400

  @raft-store-coverage
  Scenario: A StatefulSet accepts zero replicas and rejects a negative count
    Given a running cluster from topology "minimal"
    When a statefulset manifest with 0 replicas is submitted as "zero-replica-set"
    Then the manifest submission is accepted
    When a statefulset manifest with -1 replicas is submitted as "negative-replica-set"
    Then the submission is rejected with status 400

  @raft-store-coverage
  Scenario: A CronJob schedule fires on the day-of-month/day-of-week OR quirk
    Given a running cluster from topology "minimal"
    When a cronjob manifest scheduled for today's weekday but a different day of month is submitted as "or-quirk-cronjob"
    Then within 90s cronjob "or-quirk-cronjob" has at least 1 job run
