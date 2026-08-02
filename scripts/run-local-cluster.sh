#!/usr/bin/env bash
# Thin convenience wrapper around the two commands documented in gimle-console/LOCAL_DEV.md --
# one source of truth, not a second implementation: all the classpath resolution/process-spawning
# logic lives in gimle-maven-plugin's ControlPlaneMojo/AgentMojo, not here.
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/.."

if [ -z "${JAVA_HOME:-}" ]; then
  echo "JAVA_HOME must point at a JDK 25 install (see LOCAL_DEV.md step 0)" >&2
  exit 1
fi
export PATH="$JAVA_HOME/bin:$PATH"

echo "==> mvn install -DskipTests (builds Java + the console, bundles it for gimle-controlplane)"
mvn -q install -DskipTests

echo "==> building gimle-examples/hello-module"
mvn -q -pl gimle-examples/hello-module package

echo "==> launching control plane on :8080 (raft :9080), console at http://127.0.0.1:8080/console"
mvn gimle:controlplane &
CP_PID=$!

cleanup() {
  echo "==> shutting down control plane (pid $CP_PID)"
  kill "$CP_PID" 2>/dev/null || true
}
trap cleanup EXIT

sleep 2

echo "==> launching node agent node-1 (Ctrl+C stops both the agent and the control plane)"
mvn gimle:agent
