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
gimle apply -f <manifest.yaml>
gimle delete deployment <name>
gimle get nodes
gimle get node-assignments <nodeId>
gimle get tenants [id]
gimle set tenant <id> --max-memory-bytes N --max-cpu-millicores N --max-instances N
gimle delete tenant <id>
gimle get config <tenantId>
gimle set config <tenantId> <key> <value> [--encrypted]
gimle delete config <tenantId> <key>
gimle logs <target> [--category=CAT] [--follow|-f] [--since=<cursor>]
```

## Examples

```bash
# Deploy (or update) a module from its manifest
gimle apply -f gimle-examples/greeter-provider/deployment.yaml --server 127.0.0.1:8080

# List every deployment, or look up one by name
gimle get deployments --server 127.0.0.1:8080
gimle get deployments greeter-provider-deployment --server 127.0.0.1:8080

# Tail a target's logs live -- the CLI-side equivalent of the console's own Logs screen
gimle logs greeter-consumer-deployment --follow --server 127.0.0.1:8080

# Inspect which node an instance landed on, and what else is scheduled there
gimle get nodes --server 127.0.0.1:8080
gimle get node-assignments node-1 --server 127.0.0.1:8080

# Per-tenant resource caps
gimle set tenant acme --max-memory-bytes 536870912 --max-cpu-millicores 2000 --max-instances 10
```

`GIMLE_SERVER=127.0.0.1:8080` in your shell's environment removes the need to repeat `--server` on
every call above — see [Getting started](../tutorials/getting-started.md) for the one-time
`~/.m2/settings.xml` setup that also makes `mvn gimle:deploy` (a thin wrapper around `apply`)
available.
