#!/usr/bin/env bash
# Automates the build + launch steps from gimle-console/LOCAL_DEV.md (steps 1-6): install every
# module, build the hello-module example artifact, build the console and point the control plane
# at it, resolve the worker's runtime classpath, then launch a real control plane and one real node
# agent as separate processes. Deploying a module (step 7) and watching logs (step 8) are left to
# the reader -- this script only gets a real cluster up, since which deployment to apply is a choice,
# not something to hardcode.
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/.."
REPO_ROOT="$(pwd)"

if [ -z "${JAVA_HOME:-}" ]; then
  echo "JAVA_HOME must point at a JDK 25 install (see LOCAL_DEV.md step 0)" >&2
  exit 1
fi
export PATH="$JAVA_HOME/bin:$PATH"

echo "==> mvn install -DskipTests"
mvn -q install -DskipTests

echo "==> building gimle-examples/hello-module"
mvn -q -pl gimle-examples/hello-module package

echo "==> building gimle-console and copying dist/ -> console-dist/"
(cd gimle-console && bun install --frozen-lockfile && bun run build)
rm -rf console-dist
cp -r gimle-console/dist console-dist

cp_for() {
  # /dev/stdout as -Dmdep.outputFile doesn't work: Maven runs as a plain Windows process here and
  # silently writes nothing -- always resolve through a real temp file instead.
  local out
  out="$(mktemp)"
  mvn -q -pl "$1" dependency:build-classpath -Dmdep.outputFile="$out"
  cat "$out"
  rm -f "$out"
}

echo "==> resolving runtime classpaths"
CP_CONTROLPLANE="gimle-controlplane/target/classes;$(cp_for gimle-controlplane)"
CP_AGENT="gimle-agent/target/classes;$(cp_for gimle-agent)"
CP_WORKER="gimle-worker/target/classes;$(cp_for gimle-worker)"

STATE_DIR="${GIMLE_STATE_DIR:-/tmp/gimle-cp-state}"
mkdir -p "$STATE_DIR"

echo "==> launching control plane on :8080 (raft :9080), console at http://127.0.0.1:8080/console"
java -cp "$CP_CONTROLPLANE" com.gimle.controlplane.ControlPlaneMain 8080 "$STATE_DIR" 9080 \
  --console-dir "$REPO_ROOT/console-dist" &
CP_PID=$!

cleanup() {
  echo "==> shutting down control plane (pid $CP_PID)"
  kill "$CP_PID" 2>/dev/null || true
}
trap cleanup EXIT

sleep 2

echo "==> launching node agent node-1 (Ctrl+C stops both the agent and the control plane)"
java -cp "$CP_AGENT" com.gimle.agent.AgentMain node-1 http://127.0.0.1:8080 127.0.0.1:9090 - \
  "$JAVA_HOME/bin/java" -cp "$CP_WORKER" com.gimle.worker.WorkerMain
