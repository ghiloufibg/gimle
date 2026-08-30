# greeting-operator

A real, deployable Gimlé module that acts as the operator (controller) for the `custom.Greeting`
kind — the reference consumer of the Galdr operator SDK (`com.gimle.module.galdr`). Like
`greeter-provider`/`greeter-consumer`, it bundles genuine `ModuleLifecycleHooks` and
`LivenessProbe`/`ReadinessProbe` implementations inside its own jar; unlike them, it talks to the
platform's own API rather than to another module: an ordinary hosted module the platform never even
knows is an operator, authorized purely by whatever RBAC binding its own `svc:` workload principal
carries.

## Manifest

```yaml
name: com.gimle.examples.greeting.operator
version: 1.0.0
isolation:
  tier: TIER_2
resources:
  request:
    memory: 32Mi
    cpu: 20m
  limit:
    memory: 64Mi
    cpu: 100m
lifecycle:
  hooks: com.gimle.examples.greeting.operator.GreetingOperatorHooks
health:
  liveness: com.gimle.examples.greeting.operator.GreetingOperatorLivenessProbe
  readiness: com.gimle.examples.greeting.operator.GreetingOperatorReadinessProbe
```

## What it does

`onStart` opens a `GaldrOperatorLoop` over the `custom.Greeting` kind: every five seconds it reads
the full current set of Greetings through the agent-mediated control-plane relay (a level-triggered
full recompute, never a delta), logs each Greeting's `spec.message` its `spec.repeat` number of
times, and reports `{timesSaid, observedGeneration}` back as that resource's status via
`GaldrResource.reportStatus`. The read rides the instance's own minted workload-identity token; the
status write travels the relay's one typed write path (`PUT /resources/{kind}/{name}/status`),
checked server-side against the separate `{kind}/status` RBAC grant. `onStop` closes the loop.

A reconcile pass survives its own failures: one unparseable or otherwise poisonous Greeting is
caught and logged per resource, never allowed to stop the rest of the set from being said, and the
next tick retries everything anyway.

## Deploying it

`deployment.yaml` (checked in beside this README) deploys one replica into tenant `team-a`,
matching the walkthrough's `custom.Greeting` instances. The kind definition and a sample instance
live in the platform's own walkthrough; the short version:

```bash
mvn -pl gimle-examples/greeting-operator package
gimle apply -f greeting-kind.yaml       # the custom.Greeting KindDefinition
gimle apply -f hello.yaml               # a Greeting instance in team-a
gimle apply -f gimle-examples/greeting-operator/deployment.yaml
gimle get greetings --tenant team-a     # SAID column fills in once the operator has said hello
```
