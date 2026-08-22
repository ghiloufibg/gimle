---
sidebar_position: 2
---

# CLI reference

`gimle-cli` is a `kubectl`-shaped client — familiar muscle memory, with no claim of Kubernetes API
compatibility. Mirrors `GimleCli`'s own usage text directly.

## Global flags

Any order, anywhere on the command line:

- `--server host:port` — control-plane address (or set the `GIMLE_SERVER` environment variable
  instead, so you don't have to pass it on every invocation).
- `-o`/`--output table|json` — output format, default `table`.

## Verbs

```text
gimle get deployments [name]
gimle get jobs [name]
gimle get cronjobs [name]
gimle get daemonsets [name]
gimle get statefulsets [name]
gimle apply -f <manifest.yaml>   (kind: Deployment, Job, CronJob, DaemonSet, StatefulSet, or ArtifactSet, read from the file itself)
gimle delete deployment <name>
gimle delete job <name>
gimle delete cronjob <name>
gimle delete daemonset <name>
gimle delete statefulset <name>
gimle cronjob trigger <name>
gimle get nodes
gimle get node-assignments <nodeId>
gimle cordon <nodeId>
gimle uncordon <nodeId>
gimle events <deploymentName> <instanceIndex> [--limit N]
gimle get services [name]
gimle set service <name> --deployment <name> [--deployment ...] --port N [--target-port N]
                          [--tenant <id>]
gimle delete service <name>
gimle service endpoints <name>
gimle get networkpolicies [name]
gimle set networkpolicy <name> --tenant <id> [--deployment ...]
                                --allowed-caller-tenant <id> [--allowed-caller-tenant ...]
gimle delete networkpolicy <name>
gimle get tenants [id]
gimle set tenant <id> --max-memory-bytes N --max-cpu-millicores N --max-instances N
gimle delete tenant <id>
gimle get config <tenantId>
gimle set config <tenantId> <key> <value> [--encrypted]
gimle delete config <tenantId> <key>
gimle secret list <tenantId>
gimle secret get <tenantId> <key> [--version N]
gimle secret set <tenantId> <key> --value <v>
gimle secret delete <tenantId> <key> [--destroy]
gimle secret versions <tenantId> <key>
gimle secret rotate-key
gimle configmap list <tenantId>
gimle configmap get <tenantId> <name>
gimle configmap set <tenantId> <name> [--from-literal key=value ...] [--from-file path|key=path ...]
gimle configmap delete <tenantId> <name>
gimle artifact push <jar> [--tenant <id>]
gimle artifact list [moduleId]
gimle artifact get <moduleId> <version> [--to <path>]
gimle artifact delete <moduleId> <version>
gimle audit list [--principal <name>] [--resource <kind>] [--tenant <id>]
                  [--since <epochMillis>] [--limit N]
gimle logs <target> [--category=CAT] [--follow|-f] [--since=<cursor>]
gimle get roles [name]
gimle set role <name> --permission <resource>:<verb>[:<tenant>] [--permission ...]
gimle delete role <name>
gimle get rolebindings [id]
gimle set rolebinding <id> --subject user:<name>|group:<name> --role <name>
gimle delete rolebinding <id>
gimle get accounts [username]
gimle set account <username> --password <value>
gimle delete account <username>
gimle cert token create [--ttl <duration>]
gimle cert request --purpose operator|node --out-cert <path> --out-key <path> [--common-name <name>]
gimle cert status <request-id> --out-cert <path>
gimle cert approve <request-id>
gimle cert renew [--force]
```

The `cert` verbs are the operator-facing half of the node-bootstrap-CSR and certificate-rotation
flows — see [Transport security](../architecture/transport-security.md) §4/§4a/§4b for the full
picture. `token create` and `approve` need this invocation's own configured mTLS identity (`--server`
plus `gimle.tls.certFile`/`keyFile`/`caFile`); `request`/`status` deliberately don't, since they run
before that identity exists. `renew` only acts if the credential is actually due for renewal, unless
`--force`. `request`'s `--common-name` sets the CSR Subject's CN, defaulting to the local
`user.name` system property when omitted.

Unlike `config`, `secret` is a distinct top-level verb rather than a `get`/`set`/`delete` noun —
it needs two actions (`versions`, `rotate-key`) that three-verb dispatch has no shape for. Every
call is proxied by the control plane to Fafnir, the dedicated secrets service (see
[Multi-tenancy](../architecture/multi-tenancy.md#secrets) and
[Node topology](../architecture/node-topology.md#fafnir)) — never talked to directly. Each `set`
claims a new, immutable version rather than overwriting the last one; `get` defaults to the latest
version, `--version N` reads a specific one; `delete` soft-deletes by default (every version stays
recoverable), `--destroy` hard-deletes irreversibly. `rotate-key` generates a new master encryption
key and re-encrypts every existing secret under it, cluster-wide.

Like `secret`, `configmap` is a distinct top-level verb rather than a `get`/`set`/`delete` noun —
`list` here returns names scoped to one tenant-owned ConfigMap object, not the flat per-key rows
`config` returns, and `set` needs a read-then-write sequence rather than a single call. A ConfigMap
is a named, multi-key bundle a Deployment attaches by reference (`configMapRefs` in its manifest)
instead of receiving its tenant's entire flat config set — see
[Manifest schema](./manifest-schema.md#deployment-manifest-configmaprefs). `set` always writes a
partial merge (`PATCH`): it reads the ConfigMap's current version first (treating "doesn't exist
yet" as version 0) and supplies that version back to the server itself, so a caller never types a
version number by hand; a concurrent writer racing that same read is reported back as a plain
conflict, never silently retried. `--from-literal key=value` may repeat to set several keys in one
call; `--from-file path` reads a whole file's content as one key named by the file's own base name,
or `--from-file key=path` names the key explicitly.

`artifact` is a distinct top-level verb for the same reason `secret` is: `push` has no shape in
three-verb dispatch. Every call is proxied by the control plane to Andvari, the artifact registry
(see [Node topology](../architecture/node-topology.md#andvari)). `push` derives the registry
coordinate from the jar's own bundled `gimle-module.yaml` rather than taking name/version flags,
so the coordinate a jar is stored under and the identity it declares can never drift apart; a
re-push of different bytes under an existing coordinate is refused (a stored version is
immutable -- push the changed jar as a new version).

`events` returns an instance's full lifecycle timeline, newest-first; `--limit N` caps how many of
those entries print, applied client-side (the underlying `GET /events` call has no server-side
`limit` parameter of its own, unlike `audit list` below) — useful against a crash-looping instance
whose timeline would otherwise be hundreds of lines.

`service`/`networkpolicy` manage the [Service abstraction](../architecture/service-fabric.md#the-service-abstraction-a-stable-name-in-front-of-a-deployment)
— a stable name in front of a Deployment's live, ephemeral endpoints, the ClusterIP analogue named
in the platform's own network-model design. `set service` POSTs to the `/services` collection
rather than PUTting a per-name path, since a Service names itself in its own request body;
`--deployment` may repeat (the set of workload names a Service fronts) and `--target-port` defaults
to `--port` when omitted (the Service listens and forwards on the same port). `service endpoints`
resolves the Service's current live backing-instance set on every call, never a cached value, so it
never lags a reconcile interval behind. `networkpolicy` manages the accompanying NetworkPolicy
analogue — a deny-by-default restriction on which other tenants may call into one tenant's own
Services; `--tenant` is required (a NetworkPolicy always restricts exactly one tenant's own
Services), `--deployment` scopes it to specific workloads instead of the whole tenant when given,
and `--allowed-caller-tenant` may repeat once per permitted caller tenant.

`audit list` reads the cross-resource audit trail (see
[Authentication and authorization](../architecture/authn-authz.md#audit-logging)) — every
`WRITE`/`DELETE` authorization decision, allowed and denied, across both the control plane and
Fafnir. Every filter is optional and independently combinable; omitting all of them lists the most
recent events cluster-wide.

The `role`/`rolebinding`/`account` verbs manage RBAC — see
[Authentication and authorization](../architecture/authn-authz.md). `--permission` may repeat (a
role is a set of permissions); the optional third segment of `resource:verb:tenant` scopes a grant
to one tenant instead of cluster-wide. `set account` doubles as create-or-reset-password, matching
`set tenant`/`set config`'s existing create-or-update convention — the password is sent once over
the same authenticated mTLS connection every other write already uses and is hashed server-side,
never stored or echoed back in plaintext.

## Examples

```bash
# Deploy (or update) a module from its manifest
gimle apply -f gimle-examples/greeter-provider/deployment.yaml --server 127.0.0.1:8080

# List every deployment, or look up one by name
gimle get deployments --server 127.0.0.1:8080
gimle get deployments greeter-provider-deployment --server 127.0.0.1:8080

# Tail a target's logs live -- the CLI-side equivalent of the console's own Logs screen
gimle logs instance/greeter-consumer-deployment/0 --follow --server 127.0.0.1:8080

# Inspect which node an instance landed on, and what else is scheduled there
gimle get nodes --server 127.0.0.1:8080
gimle get node-assignments node-1 --server 127.0.0.1:8080

# Exclude a node from future placement without evicting what's already running there
gimle cordon node-1 --server 127.0.0.1:8080
gimle uncordon node-1 --server 127.0.0.1:8080

# An instance's own lifecycle timeline (installed, resolved, started, active, ...) -- --limit caps
# a crash-looping instance's otherwise-hundreds-of-lines timeline to the most recent entries
gimle events orders-service-deployment 0 --server 127.0.0.1:8080
gimle events orders-service-deployment 0 --limit 20 --server 127.0.0.1:8080

# A stable name in front of a Deployment's live endpoints, and who else may call it
gimle set service orders-web --deployment orders-service-deployment --port 8080 --server 127.0.0.1:8080
gimle service endpoints orders-web --server 127.0.0.1:8080
gimle set networkpolicy orders-policy --tenant orders-platform --allowed-caller-tenant billing --server 127.0.0.1:8080

# Who deleted the acme tenant's secrets in the last hour -- allowed and denied attempts alike
gimle audit list --tenant acme --resource SECRET --since 1712000000000 --server 127.0.0.1:8080

# Schedule a recurring job, list cronjobs, then fire one immediately without waiting for its
# schedule -- generated Jobs show up on `gimle get jobs`, named nightly-cleanup-<epochSeconds>
gimle apply -f cronjob.yaml --server 127.0.0.1:8080
gimle get cronjobs --server 127.0.0.1:8080
gimle cronjob trigger nightly-cleanup --server 127.0.0.1:8080

# Run one instance on every eligible node (topology-derived, no --replicas flag to set)
gimle apply -f daemonset.yaml --server 127.0.0.1:8080
gimle get daemonsets node-exporter --server 127.0.0.1:8080

# Ordered rollout, sticky per-index placement -- get shows each index's own nodeId
gimle apply -f statefulset.yaml --server 127.0.0.1:8080
gimle get statefulsets orders-statefulset --server 127.0.0.1:8080

# Push one jar, tagged with its owning tenant
gimle artifact push target/orders-service-1.0.0.jar --tenant orders-platform --server 127.0.0.1:8080

# Push a whole multi-jar app in one command instead of one `artifact push` per jar
gimle apply -f artifactset.yaml --server 127.0.0.1:8080

# Per-tenant resource caps
gimle set tenant acme --max-memory-bytes 536870912 --max-cpu-millicores 2000 --max-instances 10
```

`GIMLE_SERVER=127.0.0.1:8080` in your shell's environment removes the need to repeat `--server` on
every call above — see [Getting started](../tutorials/getting-started.md) for the one-time
`~/.m2/settings.xml` setup that also makes `mvn gimle:deploy` (a thin wrapper around `apply`)
available.
