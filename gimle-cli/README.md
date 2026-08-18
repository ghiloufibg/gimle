# Gimle CLI

`gimle-cli` builds `gimle`, the primary end-user and operator command-line client for the control
plane's HTTP API — a `kubectl`-shaped surface (verb-then-noun dispatch, `apply -f`, `-o table|json`)
chosen for familiar muscle memory, with no claim of Kubernetes API compatibility. It is the thing a
person actually types; `gimle-agent`/`gimle-worker` launchers are separate modules, not part of this
one's surface.

Every command talks to the control plane over `ControlPlaneClient`, which wraps `HttpClient` with a
fixed request timeout and turns a non-2xx response into a `CliException` rather than leaving a raw
response for callers to inspect. `HttpClient.Redirect.NORMAL` transparently follows the control
plane's `307` not-the-leader response (redirecting while preserving the original HTTP method), so a
write sent to any reachable replica reaches the current Raft leader without the caller doing
anything special — only a `307` with no `Location` header (leader currently unknown) surfaces as an
error. Transport is plain `http://` by default, or full mTLS `https://` via `gimle.tls.*` system
properties when `gimle.transport.protocol=tls` is set.

## Global flags

Recognized anywhere in the argument list, any order:

- `--server host:port` — the control plane to talk to (or set the `GIMLE_SERVER` env var).
- `-o`/`--output table|json` — output format, default `table`.

## Verb surface

```
gimle get deployments [name]
gimle get jobs [name]
gimle get cronjobs [name]
gimle get daemonsets [name]
gimle get statefulsets [name]
gimle apply -f <manifest.yaml>   (kind: Deployment, Job, CronJob, DaemonSet, or StatefulSet,
                                  read from the file itself)
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
gimle events <deploymentName> <instanceIndex>

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

gimle artifact push <jar>
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
gimle cert request --purpose operator|node --out-cert <path> --out-key <path>
gimle cert status <request-id> --out-cert <path>
gimle cert approve <request-id>
gimle cert renew [--force]
```

`GimleCli` dispatches most of the above through three shared three-verb handlers (`get`/`set`/
`delete`, noun-routed). A handful of nouns don't fit that shape and get their own top-level verb
instead: `secret`/`secrets` (needs `rotate-key`, `versions`, `--destroy` — no plain get/set/delete
shape), `artifact`/`artifacts` (`push` has no equivalent), `cronjob`/`cronjobs` (needs `trigger`
alongside its ordinary get/apply/delete), `audit` (read-only, its own filter set), `logs`, `cordon`/
`uncordon`, and `events`. `apply -f` is kind-dispatched rather than noun-dispatched: it peeks at the
manifest file's own `kind:` field to route to the right command, the same `kubectl apply -f x.yaml`
convention.

### Notable command behavior

- **`apply`** — `DeploymentsCommand`/`JobsCommand`/`CronJobsCommand`/`DaemonSetsCommand`/
  `StatefulSetsCommand`, one per workload manifest kind; each independently re-reads the manifest
  file for its own `name:` extraction and PUT.
- **`logs`** — a real `kubectl logs` equivalent (`LogsCommand`), sharing the identical backend
  routes and JSON shapes the console's `src/repositories/http/logs.ts` uses. Without `--follow`, one
  request, print, exit; with `--follow`, opens the same chunked stream the console's live tail reads
  from and prints lines as they arrive until interrupted.
- **`secret`/`artifact`** — proxied through the control plane's `/secrets/*` and `/artifacts/*`
  surfaces to `gimle-fafnir`/`gimle-andvari` respectively, never talking to either process directly.
  `artifact push` derives its registry coordinate from the jar's own bundled `gimle-module.yaml`
  (`ModuleArtifactReader`) rather than taking name/version flags, so a jar's stored coordinate and
  its self-declared identity can never drift apart.
- **`cert`** (`CertCommand`) — the operator-facing half of node join and certificate rotation.
  `cert request`/`cert status` run before the caller has a client certificate of its own, so they
  build a trust-only client (verifies the server against `gimle.tls.caFile`, presents nothing);
  every other `cert` subcommand, and every other verb in the CLI, uses a fully-authenticated mTLS
  client. On every invocation (any verb, not just `cert`), the CLI best-effort checks whether the
  caller's own credential is due for renewal and prints a warning if so — it never renews silently;
  `cert renew` is always the user's own explicit action.
- **`--server`/`GIMLE_SERVER`** — required for every verb except `cert`, which needs the server
  address but not a fully-provisioned client.

## Module layout

- `com.gimle.cli.GimleCli` — entry point, global-flag parsing, verb/noun dispatch.
- `com.gimle.cli.ControlPlaneClient` — shared HTTP calling logic (timeouts, TLS, redirect-following,
  status-code checking).
- One command class per resource/verb group (`DeploymentsCommand`, `JobsCommand`, `CronJobsCommand`,
  `DaemonSetsCommand`, `StatefulSetsCommand`, `NodesCommand`, `TenantsCommand`, `ConfigCommand`,
  `SecretCommand`, `ArtifactCommand`, `LogsCommand`, `EventsCommand`, `AuditCommand`, `RolesCommand`,
  `RoleBindingsCommand`, `AccountsCommand`, `CertCommand`).
- `ApiResponse`, `Flags`, `ManifestFiles`, `OutputFormat`, `CliException` — shared parsing/formatting
  plumbing.

Depends on `gimle-pki` (CSR generation for `cert request` — the CLI generates its own CSRs locally;
signing only ever happens on the control plane) and `gimle-module` (`ModuleArtifactReader` for
`artifact push`'s coordinate derivation). `gimle-controlplane` and `gimle-fafnir` are test-scoped
only, backing `GimleCliTest`'s in-process fixture cluster.
