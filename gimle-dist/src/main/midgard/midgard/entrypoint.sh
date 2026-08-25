#!/bin/bash
# Container entrypoint: boots the bundled single-machine Gimlé cluster via "hilmir up", optionally
# seeds the example modules, then stays in the foreground watching the control plane's port so the
# container's own lifetime tracks the cluster's. On SIGTERM/SIGINT (docker stop), tears the whole
# cluster back down through "hilmir down" before exiting.
#
# "hilmir up" spawns each platform process detached and returns once everything on this machine is
# reachable; the spawned processes are reparented to PID 1, which is why the image must run under
# an init process (docker-compose.yaml sets init: true; plain docker run needs --init) -- without
# one, exited worker JVMs would accumulate as zombies with nothing reaping them.
set -eu

data_root=/var/lib/gimle
topology=/opt/gimle/midgard/topology.yaml

/opt/gimle/bin/hilmir up -f "$topology" --machine midgard

if [ "${MIDGARD_SEED:-true}" != "false" ]; then
  /opt/gimle/midgard/seed-examples.sh
fi

echo "midgard: cluster is up"
echo "midgard:   control plane API + web console  http://localhost:8080  (console at /console)"
echo "midgard:   fafnir (secrets) API + console   http://localhost:9092  (console at /console)"
echo "midgard:   muninn (observability) API       http://localhost:9093"
echo "midgard:   andvari (artifacts) API + console http://localhost:9094  (console at /console)"

shut_down() {
  echo "midgard: stopping cluster..."
  /opt/gimle/bin/hilmir down --machine midgard --data-root "$data_root" || true
  exit 0
}
trap shut_down TERM INT

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
