---
sidebar_position: 1
---

# Getting started

The genuine end-to-end path: build every module for real, then launch a real control plane and a
real node agent as separate OS processes.

## Prerequisites

- JDK 25 on `PATH`/`JAVA_HOME`.
- [Bun](https://bun.sh/) on `PATH` — Maven shells out to it to build `gimle-console` (the web
  console) and this documentation site.

One-time setup, once per developer machine: `com.gimle` is a private groupId (never published to
Central), so Maven's short `gimle:*` plugin-prefix form needs an explicit entry in
`~/.m2/settings.xml`:

```xml
<settings>
  <pluginGroups>
    <pluginGroup>com.gimle</pluginGroup>
  </pluginGroups>
</settings>
```

## 1. Build everything

```bash
cd gimle
mvn install -DskipTests
```

One command builds every Java module and the web console (Bun install, Vite build, `bun test`),
then bundles the built SPA into `gimle-console`'s own jar so `gimle-controlplane` can serve it
with no separate build/copy step.

## 2. Launch a control plane and a node agent

```bash
mvn gimle:controlplane
mvn gimle:agent
```

Each is a `spring-boot:run`-style goal from a small custom Maven plugin
(`gimle-maven-plugin/`) — no `-pl <module>`, no manual classpath resolution. Run each in its own
terminal; they stay in the foreground.

## 3. Deploy a module

```bash
mvn gimle:deploy -Dgimle.deploy.file=gimle-examples/hello-module/deployment.yaml
```

See [Deploy your first module](./deploy-your-first-module.md) for a full walkthrough using the
real `greeter-provider`/`greeter-consumer` example pair, and
[Local dev cluster](./local-dev-cluster.md) for the fully-automated version of steps 1–2.
