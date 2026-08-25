#!/bin/bash
# Seeds the freshly-booted cluster with the bundled example modules so a QA session starts against
# real running workloads instead of an empty cluster: every jar under /opt/gimle/examples is pushed
# to the Andvari artifact registry (through the control plane's own /artifacts/* proxy), then every
# manifest under /opt/gimle/midgard/manifests is applied, in filename order (the numeric prefixes
# put the greeter provider ahead of the consumer that calls it). Both steps are idempotent -- an
# identical re-push is a no-op and apply is an upsert -- so re-running on container restart is safe.
set -euo pipefail

server=127.0.0.1:8080

for jar in /opt/gimle/examples/*.jar; do
  /opt/gimle/bin/gimle artifact push "$jar" --server "$server"
done

for manifest in /opt/gimle/midgard/manifests/*.yaml; do
  /opt/gimle/bin/gimle apply -f "$manifest" --server "$server"
done

echo "midgard: seeded example modules -- instances converge to ACTIVE within a few reconcile ticks"
