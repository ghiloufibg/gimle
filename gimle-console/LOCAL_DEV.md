# Running a real local Gimlé cluster + console

This is the genuine end-to-end path: build every module for real, launch a real control plane and
a real node agent as separate OS processes, deploy a real module artifact, and watch it go `ACTIVE`
in the console — no mocks, no curl-seeded fake state. All commands below are Git Bash (the shell
this repo's other docs assume on Windows); adjust quoting for PowerShell/POSIX shells as needed.

Everything here runs on one machine, launched via three `mvn gimle:*` commands (a small custom
Maven plugin in `gimle-maven-plugin/`, `spring-boot:run`-style — no `-pl <module>`, no manual
classpath resolution, no shell-stitched `java -cp ...` invocations). `JAVA_HOME` must point at a
JDK 25 install for every step, and `bun` must be on `PATH` (Maven shells out to it — see
`gimle-console/pom.xml`).

Steps 1–5 below (build everything, launch the control plane and one node agent) are automated by
`scripts/run-local-cluster.sh` — run it directly if you just want a cluster up, or read on for what
it does and how to do steps 6–7 (deploy the example module, watch logs) by hand.

## 0. Prerequisites

```bash
export JAVA_HOME="/c/Program Files/Java/jdk-25.0.1"
export PATH="$JAVA_HOME/bin:$PATH"
java -version   # confirm 25.x
```

**One-time setup, once per developer machine**: the `mvn gimle:*` commands below use Maven's short
plugin-prefix form. `com.gimle` is a private groupId (never published to Central), so it needs an
explicit entry in `~/.m2/settings.xml` — without this, Maven can't resolve `gimle:controlplane` to
`com.gimle:gimle-maven-plugin:<version>:controlplane`:

```xml
<settings>
  <pluginGroups>
    <pluginGroup>com.gimle</pluginGroup>
  </pluginGroups>
</settings>
```

## 1. Build everything (Java and the console) and install to the local Maven repo

```bash
cd "C:\Users\PC\IdeaProjects\gimle"
mvn install -DskipTests
```

One command builds every Java module *and* `gimle-console` (Bun install, `vite build`, `bun test`,
via `exec-maven-plugin` — see `gimle-console/pom.xml`), then bundles the built SPA into
`gimle-console`'s own jar (`console/**`) so `gimle-controlplane` can depend on and serve it with no
separate build/copy step. `-DskipTests` only skips the *Java* test suite here for a fast local
run — the console's own `bun test` still runs as part of its build regardless, since it's wired to
a Maven phase (`test`) that fires either way. Drop `-DskipTests` for the full CI-equivalent pass.

This is also the step to re-run after any code change — the `gimle:*` goals below launch whatever
is already sitting in each module's `target/classes` (like most Java "build once, run many times"
workflows), they don't recompile on their own.

## 2. Build the example module artifact

```bash
mvn -pl gimle-examples/hello-module package
```

Produces `gimle-examples/hello-module/target/hello-module-0.1.0-SNAPSHOT.jar` — a real jar containing
both `module-info.class` and `META-INF/gimle/gimle-module.yaml`, exactly what `ModuleArtifactReader`
requires. `gimle-examples/hello-module/deployment.yaml` (checked in) already points at it.

## 3. Launch the control plane

```bash
mvn gimle:controlplane
```

No `-pl`, no classpath flags — `ControlPlaneMojo` resolves `gimle-controlplane`'s own runtime
classpath automatically and spawns a real `ControlPlaneMain` process (port `8080`, Raft `9080`,
state under `gimle-controlplane/target/gimle-state` by default — `mvn clean` resets it). Every
other reactor module's own `gimle:controlplane` execution no-ops immediately (matching one specific
`artifactId`, see `AbstractGimleMojo`). Override defaults with `-Dgimle.controlplane.port=`,
`-Dgimle.controlplane.stateDir=`, `-Dgimle.controlplane.raftPort=`. Leave this running in its own
terminal. Once it logs that it's serving, `http://127.0.0.1:8080/console` should load the console
shell (with an empty/loading state — no agent has registered yet).

## 4. Launch one node agent

In a second terminal (re-export `JAVA_HOME`/`PATH` from step 0 if this is a fresh shell):

```bash
mvn gimle:agent
```

`AgentMojo` resolves both `gimle-agent`'s own classpath *and* `gimle-worker`'s classpath (a
genuinely separate process/classpath the agent spawns worker JVMs with — the worker's classpath is
resolved directly against the already-`mvn install`ed `com.gimle:gimle-worker` artifact via Maven's
own dependency resolver, independent of `-pl`). Defaults: node id `node-1`, control plane at
`http://127.0.0.1:8080`, gossip on `127.0.0.1:9090` — override with `-Dgimle.agent.nodeId=`,
`-Dgimle.agent.controlPlaneUrl=`, `-Dgimle.agent.gossipAddress=` (e.g. to run a second agent
alongside the first).

Refresh `/console` → the Nodes screen should now show `node-1` with real reported capacity.

## 5. Deploy the example module

Either through the console's "New deployment" form, or with:

```bash
mvn gimle:deploy -Dgimle.deploy.file=gimle-examples/hello-module/deployment.yaml
```

`gimle.deploy.server` defaults to `127.0.0.1:8080`, overridable the same way. Within a couple of
reconcile ticks (2s interval), the Instances screen should show one instance of `hello-deployment`
reaching `ACTIVE`, and Topology/Metrics should reflect a real running worker JVM — not seeded data.

For anything beyond `apply` (arbitrary `get`/`set`/`delete`/`logs` verbs), use the CLI directly:
`gimle-cli`'s own runtime classpath still resolves the manual way (`mvn -pl gimle-cli
dependency:build-classpath -Dmdep.outputFile=<file>`, then `java -cp
gimle-cli/target/classes;<file-contents> com.gimle.cli.GimleCli <verb> ... --server
127.0.0.1:8080`) — `gimle:deploy` only wraps the one `apply` case developers reach for constantly.

## 6. Watch real logs, including live tail

From the console's Logs screen, pick the control plane, `node-1`, or the `hello-deployment`
instance, and confirm real lines appear; toggle "follow" and confirm new lines arrive as the
process keeps running.

The same data is available from the CLI (see step 5's note on running it directly), as a genuine
`kubectl logs -f` equivalent — `GimleCli logs controlplane --follow --server 127.0.0.1:8080`.
Running this side-by-side with the console's own "follow" toggle on the same target is the real
proof that one backend mechanism (the control plane's `/logs/*` routes, proxying to
`AgentLogServer` where needed) serves both consumers identically.

## 7. Shut down

`Ctrl+C` the agent terminal first (it tears down its supervised worker), then the control plane
terminal. `gimle-controlplane/target/gimle-state` holds Raft/state-store data across restarts;
`mvn clean` (or delete it directly) for a clean slate.

## Iterating on the console UI itself

For frontend-only iteration, skip rebuilding/reinstalling the `gimle-console` Maven module every
time — run the Vite dev server directly, which proxies `/deployments`, `/nodes`, `/logs`, etc. to a
control plane already running per steps 3–4 (see `gimle-console/vite.config.ts`):

```bash
cd gimle-console
GIMLE_CONTROLPLANE_PORT=8080 bun run dev
```
