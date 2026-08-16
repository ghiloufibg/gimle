#!/bin/sh
# Runs one machine's own role(s) via a real "hilmir up" against the topology mounted at
# /config/topology.yaml, then keeps this container's own PID 1 alive for as long as the real
# spawned process(es) stay alive.
#
# "hilmir up" itself spawns every process this machine hosts as a detached OS child and returns
# immediately (see MachineLauncher's own javadoc) -- it never blocks waiting on them, so something
# has to. Tracking the spawned process's own PID directly from this shell is not reliable: by the
# time hilmir's own JVM has exited, that process may already be a grandchild re-parented away from
# this shell's own process tree. Polling "hilmir status" instead -- the same real verb an operator
# would run by hand -- is honest and uses only already-existing, already-tested hilmir surface.
set -eu

machine="${1:?usage: entrypoint.sh <machine-name>}"

echo "entrypoint: starting machine '$machine' via hilmir up"
/opt/gimle/bin/hilmir up -f /config/topology.yaml --machine "$machine"

while true; do
  sleep 10
  status_output=$(/opt/gimle/bin/hilmir status --machine "$machine" --data-root /data)
  if echo "$status_output" | grep -q "alive=false"; then
    echo "entrypoint: a process on machine '$machine' died:"
    echo "$status_output"
    exit 1
  fi
done
