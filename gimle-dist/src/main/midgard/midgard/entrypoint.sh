#!/bin/bash
# Container entrypoint: boots the bundled single-machine Gimlé cluster via "hilmir up", seeds the
# example modules on first boot, then stays in the foreground watching the control plane's port so
# the container's own lifetime tracks the cluster's. On SIGTERM/SIGINT (docker stop), tears the
# whole cluster back down through "hilmir down" before exiting.
#
# "hilmir up" spawns each platform process detached and returns once everything on this machine is
# reachable; the spawned processes are reparented to PID 1, which is why the image should run under
# an init process (docker-compose.yaml sets init: true; plain docker run should pass --init): an
# init guarantees exited children are reaped. Running this script itself as PID 1 happens to work
# too -- bash reaps reparented children -- but that is incidental shell behavior, not a contract.
set -eu

data_root=/var/lib/gimle
topology=/opt/gimle/midgard/topology.yaml
seed_marker="$data_root/midgard-seeded"

shut_down() {
  echo "midgard: stopping cluster..."
  /opt/gimle/bin/hilmir down --machine midgard --data-root "$data_root" || true
  exit 0
}
# Installed before "hilmir up", not after: a docker stop arriving mid-boot must still tear down
# whatever was already spawned (hilmir writes a partial run ledger exactly for that case). Bash
# defers the trap until the currently-running foreground command returns, so a stop during boot
# takes effect the moment "hilmir up" itself finishes.
trap shut_down TERM INT

/opt/gimle/bin/hilmir up -f "$topology" --machine midgard

# Seed once per data volume, marked on the volume itself: re-applying the bundled manifests on
# every restart would silently revert anything a user changed about the example deployments
# (a scale-up, a deletion) -- exactly the state the volume promises to keep. A failed attempt is
# retried, and a seeding failure never takes the just-booted cluster down with it: the cluster
# stays up unseeded and the marker stays absent, so the next restart (or a manual
# "docker exec gimle-midgard /opt/gimle/midgard/seed-examples.sh") tries again.
if [ "${MIDGARD_SEED:-true}" != "false" ] && [ ! -f "$seed_marker" ]; then
  seeded=false
  for attempt in 1 2 3; do
    if /opt/gimle/midgard/seed-examples.sh; then
      seeded=true
      break
    fi
    echo "midgard: seeding attempt ${attempt} failed, retrying..." >&2
    sleep 5
  done
  if [ "$seeded" = true ]; then
    touch "$seed_marker"
  else
    echo "midgard: seeding failed after 3 attempts -- cluster is up but unseeded; run" >&2
    echo "midgard: docker exec gimle-midgard /opt/gimle/midgard/seed-examples.sh to retry" >&2
  fi
fi

echo "midgard: cluster is up"
echo "midgard:   control plane API + web console  http://localhost:8080  (console at /console)"
echo "midgard:   fafnir (secrets) API + console   http://localhost:9092  (console at /console)"
echo "midgard:   muninn (observability) API       http://localhost:9093"
echo "midgard:   andvari (artifacts) API + console http://localhost:9094  (console at /console)"

# Cheap liveness watch: a bare TCP connect to the control plane port every few seconds, tolerating
# short blips (a control plane restarting under a supervisor should not kill the whole container).
# "sleep in the background + wait" rather than a plain sleep so the TERM trap fires immediately
# instead of after the current sleep finishes.
consecutive_failures=0
while true; do
  sleep 5 &
  wait $! || true
  if (exec 3<>/dev/tcp/127.0.0.1/8080) 2>/dev/null; then
    consecutive_failures=0
  else
    consecutive_failures=$((consecutive_failures + 1))
    if [ "$consecutive_failures" -ge 6 ]; then
      echo "midgard: control plane unreachable for ${consecutive_failures} consecutive checks -- exiting" >&2
      /opt/gimle/bin/hilmir down --machine midgard --data-root "$data_root" || true
      exit 1
    fi
  fi
done
