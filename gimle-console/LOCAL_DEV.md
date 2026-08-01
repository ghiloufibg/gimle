# Running a real local Gimlé cluster + console

This is the genuine end-to-end path: build every module for real, launch a real control plane and
a real node agent as separate OS processes, deploy a real module artifact, and watch it go `ACTIVE`
in the console — no mocks, no curl-seeded fake state. All commands below are Git Bash (the shell
this repo's other docs assume on Windows); adjust quoting for PowerShell/POSIX shells as needed.

Everything here runs on one machine. `JAVA_HOME` must point at a JDK 25 install for every step.

Steps 1–6 below (build everything, build the console, launch the control plane and one node agent)
are automated by `scripts/run-local-cluster.sh` — run it directly if you just want a cluster up, or
read on for what it does and how to do steps 7–8 (deploy the example module, watch logs) by hand.

## 0. Prerequisites

```bash
export JAVA_HOME="/c/Program Files/Java/jdk-25.0.1"
export PATH="$JAVA_HOME/bin:$PATH"
java -version   # confirm 25.x
```

`gimle-console/` needs [Bun](https://bun.sh) installed separately; it is not part of the Maven reactor.

## 1. Build everything and install to the local Maven repo

```bash
cd "C:\Users\PC\IdeaProjects\gimle"
mvn install -DskipTests
```

`-DskipTests` is only to make this step fast for a local run — `mvn verify` (no skip) is what CI-equivalent
verification uses; run that instead if you want the full test suite to gate the build.

Installing (not just `package`) matters: step 3 resolves gimle-worker's runtime classpath out of the
local `~/.m2` repo, which only has jars there once they're installed.

## 2. Build the example module artifact

```bash
mvn -pl gimle-examples/hello-module package
```

Produces `gimle-examples/hello-module/target/hello-module-0.1.0-SNAPSHOT.jar` — a real jar containing
both `module-info.class` and `META-INF/gimle/gimle-module.yaml`, exactly what `ModuleArtifactReader`
requires. Note its absolute path; you'll need it in step 6.

## 3. Build the console and point the control plane at it

```bash
cd gimle-console
bun install
bun run build
cd ..
rm -rf console-dist
cp -r gimle-console/dist console-dist
```

`console-dist/` (`index.html` + `assets/*`) is what `ControlPlaneMain --console-dir` serves as static
content at `/console` — see `claudedocs/web-console-design.md` §11.

## 4. Resolve runtime classpaths

`gimle-worker`, `gimle-controlplane`, `gimle-agent`, and `gimle-cli` are each launched as a plain
`java -cp <classpath> ...` process (the same pattern this repo's own integration tests use, e.g.
`WorkerProcessSupervisorTest`) — no uber-jar, no module-path, just a classpath built from the
reactor's installed jars and their dependencies. `-Dmdep.outputFile=/dev/stdout` does **not** work
here (Maven runs as a plain Windows process and silently writes nothing) — always give it a real
file path:

```bash
for m in gimle-worker gimle-controlplane gimle-agent gimle-cli; do
  mvn -q -pl "$m" dependency:build-classpath -Dmdep.outputFile="/tmp/$m-cp.txt"
done
export WORKER_CP="gimle-worker/target/classes;$(cat /tmp/gimle-worker-cp.txt)"
export CONTROLPLANE_CP="gimle-controlplane/target/classes;$(cat /tmp/gimle-controlplane-cp.txt)"
export AGENT_CP="gimle-agent/target/classes;$(cat /tmp/gimle-agent-cp.txt)"
export CLI_CP="gimle-cli/target/classes;$(cat /tmp/gimle-cli-cp.txt)"
```

(Windows classpath separator is `;`; use `:` on Linux/macOS.)

## 5. Launch the control plane

```bash
mkdir -p /tmp/gimle-cp-state
java -cp "$CONTROLPLANE_CP" com.gimle.controlplane.ControlPlaneMain 8080 /tmp/gimle-cp-state 9080 \
  --console-dir console-dist
```

Leave this running in its own terminal. Once it logs that it's serving, `http://127.0.0.1:8080/console`
should load the console shell (with an empty/loading state — no agent has registered yet).

## 6. Launch one node agent

In a second terminal (same `JAVA_HOME`/`PATH` exports from step 0, and re-export `WORKER_CP`/`AGENT_CP`
from step 4 if this is a fresh shell):

```bash
java -cp "$AGENT_CP" com.gimle.agent.AgentMain node-1 http://127.0.0.1:8080 127.0.0.1:9090 - \
  "$JAVA_HOME/bin/java" -cp "$WORKER_CP" com.gimle.worker.WorkerMain
```

Argument shapes, left to right: `nodeId` `controlPlaneBaseUrl` `gossipBindHost:port` `seeds` (`-` = no
seeds, this is the only node) `javaExecutable` `<worker-command-tail...>` — the agent appends
`WorkerMain`'s `<nodeId> <tenantId>` itself, then `WorkerProcessSupervisor` appends the control-socket
path last, so the tail above stops right after the class name.

Refresh `/console` → the Nodes screen should now show `node-1` with real reported capacity.

## 7. Deploy the example module

Either through the console's "New deployment" form, or with the CLI:

```bash
cat > /tmp/hello-deployment.yaml <<'EOF'
name: hello-deployment
module:
  name: com.gimle.examples.hello
  version: 1.0.0
artifactPath: C:\Users\PC\IdeaProjects\gimle\gimle-examples\hello-module\target\hello-module-0.1.0-SNAPSHOT.jar
replicas: 1
EOF

java -cp "$CLI_CP" com.gimle.cli.GimleCli apply -f /tmp/hello-deployment.yaml --server 127.0.0.1:8080
```

Within a couple of reconcile ticks (2s interval), the Instances screen should show one instance of
`hello-deployment` reaching `ACTIVE`, and Topology/Metrics should reflect a real running worker JVM —
not seeded data.

## 8. Watch real logs, including live tail

From the console's Logs screen, pick the control plane, `node-1`, or the `hello-deployment` instance,
and confirm real lines appear; toggle "follow" and confirm new lines arrive as the process keeps running.

The same data is available from the CLI, as a genuine `kubectl logs -f` equivalent:

```bash
java -cp "$CLI_CP" com.gimle.cli.GimleCli logs controlplane --follow --server 127.0.0.1:8080
```

Running this side-by-side with the console's own "follow" toggle on the same target is the real proof
that one backend mechanism (the control plane's `/logs/*` routes, proxying to `AgentLogServer` where
needed) serves both consumers identically.

## 9. Shut down

`Ctrl+C` the agent terminal first (it tears down its supervised worker), then the control plane
terminal. `/tmp/gimle-cp-state` holds Raft/state-store data across restarts; delete it for a clean slate.

## Iterating on the console UI itself

For frontend-only iteration against a cluster already running per steps 5–7, skip the build/copy in
step 3 and instead run the Vite dev server, which proxies `/deployments`, `/nodes`, `/logs`, etc. to
the real control plane (see `gimle-console/vite.config.ts`):

```bash
cd gimle-console
GIMLE_CONTROLPLANE_PORT=8080 bun run dev
```
