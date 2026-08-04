---
sidebar_position: 2
---

# Local dev cluster

The genuine end-to-end path: build every module for real, launch a real store, control plane, and
node agent as separate OS processes, deploy a real module artifact, and watch it go `ACTIVE` in the
web console — no mocks, no seeded fake state.

Full step-by-step walkthrough lives in `gimle-console/LOCAL_DEV.md`, not duplicated here, so there
is exactly one copy to keep in sync as commands or defaults change. It covers:

- One-time `JAVA_HOME`/`~/.m2/settings.xml` setup (the same setup
  [Getting started](./getting-started.md) covers for a single deploy).
- `mvn install -DskipTests` to build every Java module *and* the web console in one command.
- `mvn gimle:store` / `mvn gimle:controlplane` / `mvn gimle:agent` to launch a real store, control
  plane, and node agent, each in its own terminal — see
  [Control plane](../architecture/control-plane.md) for why the store is its own process.
- Deploying `hello-module` and watching it reach `ACTIVE` in the console's Nodes/Instances/Topology
  screens with real reported data.
- Live log tailing, from both the console's Logs screen and `gimle logs --follow` side by side —
  proof both consumers hit the same backend mechanism.
- `scripts/run-local-cluster.sh`, which automates the build-and-launch steps if you just want a
  cluster up without doing each step by hand.

See [Deploy your first module](./deploy-your-first-module.md) for the `greeter-provider`/
`greeter-consumer` pair once a cluster is running, and
[CLI reference](../reference/cli-reference.md) for every `gimle` verb beyond `apply`.
