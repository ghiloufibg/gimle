---
sidebar_position: 1
---

import Tabs from '@theme/Tabs';
import TabItem from '@theme/TabItem';

# Getting started

The genuine end-to-end path: build every module for real, then launch a real control plane and a
real node agent as separate OS processes.

## Prerequisites

- JDK 25 on `PATH`/`JAVA_HOME`.
- [Bun](https://bun.sh/) on `PATH` — Maven shells out to it to build `gimle-console` (the web
  console) and this documentation site.

<Tabs groupId="shell">
  <TabItem value="bash" label="Git Bash">

```bash
export JAVA_HOME="/c/Program Files/Java/jdk-25.0.1"
export PATH="$JAVA_HOME/bin:$PATH"
java -version   # confirm 25.x
```

  </TabItem>
  <TabItem value="powershell" label="PowerShell">

```powershell
$env:JAVA_HOME = "C:\Program Files\Java\jdk-25.0.1"
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
java -version   # confirm 25.x
```

  </TabItem>
</Tabs>

:::tip
Every other command on this page (`mvn ...`) runs identically in either shell — only environment
variable syntax differs, which is why only this one step is tabbed.
:::

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
