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

# Docker Desktop's own Logs tab (and "docker compose logs -f") only ever see whatever this
# entrypoint's own PID 1 writes to stdout/stderr -- but "hilmir up" above redirects every process
# it just spawned straight into its own "<id>.log" file under /data instead (see MachineLauncher's
# own redirectOutput), so none of that ever reached PID 1's own streams. That redirected file is
# separate from the per-process JSON trail under "<id>-logs/" that GimleLogging's own
# PlatformFileAppender writes and MuninnShipper ships from -- this tail is a read-only, additive
# tap on the console file only, so nothing about how a process writes or ships its structured logs
# to Muninn changes. It just mirrors the same console bytes into this container's stdout too, so
# "docker logs"/Docker Desktop shows real activity instead of only this script's own two-line
# startup banner and 10s status polls.
for log_file in /data/*.log; do
  [ -e "$log_file" ] || continue
  tail -n +1 -F "$log_file" &
done

while true; do
  sleep 10
  status_output=$(/opt/gimle/bin/hilmir status --machine "$machine" --data-root /data)
  if echo "$status_output" | grep -q "alive=false"; then
    echo "entrypoint: a process on machine '$machine' died:"
    echo "$status_output"
    exit 1
  fi
done
