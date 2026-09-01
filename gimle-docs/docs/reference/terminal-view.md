---
sidebar_position: 9
---

# Terminal cluster view (`gimle top`)

A live, read-only dashboard of nodes and instances, in the terminal you are already in. Where
`gimle get nodes` and `gimle get deployments` each answer a question once, `gimle top` keeps
answering it — which is what you want while watching a change settle.

```bash
gimle top --server 127.0.0.1:8080
```

It refreshes every two seconds, needs an interactive terminal, and quits on `q` leaving the
terminal exactly as it found it.

## What it shows

**Cluster view** — the default screen:

- A status line: the control-plane address, whether the last poll succeeded, node and instance
  counts, and an ok / warn / bad instance split that always sums to the instance count.
- A node table: state (`READY`, `CORDONED`, `STALE`, `UNKNOWN`), CPU and memory against capacity
  with a bar each, how many instances are placed there, and heartbeat age.
- An instance table: deployment, index, node, lifecycle state, readiness, request rate, error rate,
  queue depth, memory and CPU.

An instance the scheduler has placed but whose node has not reported on yet reads `PENDING` with
every metric shown as `—`. That is deliberate: a zero there would look like a running instance
doing nothing.

**Instance view** — `⏎` on a selected row:

- The instance's state, liveness, readiness, module coordinate and worker id.
- Its measured request rate, error rate, queue depth, memory and CPU.
- Its recent lifecycle transitions, from the same timeline `gimle events` prints.
- A live tail of its own logs, seeded with recent backlog so a quiet instance still shows the lines
  that explain how it got here. `c` switches between the `APPLICATION` and `PLATFORM` categories.

## Keys

| Key | Does |
| --- | --- |
| `↑` `↓` / `j` `k` | move the selection |
| `⏎` | inspect the selected instance |
| `esc` | back to the cluster view |
| `/` | filter; `enter` applies, `esc` clears |
| `p` | pause / resume refresh |
| `r` | refresh now |
| `c` | cycle the log category (instance view) |
| `g` / `G` | jump to the top / bottom |
| `?` | help |
| `q` / `ctrl-c` | quit, restoring the terminal |

The filter matches across deployment name, instance index, node, lifecycle state, tenant and module
coordinate, so typing what you remember tends to be enough.

## It cannot change anything

There is no cordon, no delete, no rollback, no scale — not behind a confirmation prompt, not
anywhere. The view is handed a read-only view of the control-plane API rather than the client the
other verbs use, so a mutation is not reachable rather than merely discouraged. It sees exactly what
your own certificate already permits on a `GET`, and nothing was added server-side to widen that.

Use the ordinary verbs to change something, and watch the result here.

## Colour

Colour comes from the console's own dark-theme design tokens, converted once from OKLCH to sRGB, so
a state that reads amber in the browser reads amber here. It degrades in two steps and never
carries meaning on its own:

- **Truecolor** — the default, exact token values via 24-bit escape sequences.
- **256-colour** — each token approximated into the xterm palette.
- **No colour** — `NO_COLOR` set, `TERM=dumb`, or no TTY attached. Every state still reads as
  words.

## Requirements and limits

- **An interactive terminal.** There is no pipe-friendly mode; that is what `gimle get nodes -o
  json` is for. A terminal that reports itself as dumb is refused with a message saying so rather
  than filled with escape sequences it cannot interpret.
- **Native access.** Raw terminal mode is reached through the Foreign Function &amp; Memory API, so
  the JVM needs `--enable-native-access=ALL-UNNAMED`. The `bin/gimle` launcher in every
  distribution archive already passes it; a hand-rolled `java -cp ... com.gimle.cli.GimleCli`
  invocation needs it added.
- **Deployments only.** The instance table is built from `GET /deployments`. Jobs, cron jobs,
  daemon sets and stateful sets have their own `gimle get` verbs and do not appear here.
- **No resource limits or isolation tier** in the instance view. Those live in the module's own
  descriptor, which no read route serves, and this view adds no server-side surface of its own.
- **One cluster.** No context switching; point `--server`/`GIMLE_SERVER` at the one you want.
- **No mouse.**

## Where it lives

The verb is not built into `gimle-cli`. It is contributed by `gimle-hugin`, a separate reactor
module discovered through `java.util.ServiceLoader` at dispatch time: `gimle-cli` declares a
`CliExtension` interface and looks one up immediately before its own unknown-verb error, and
`gimle-hugin` provides one. Take `gimle-hugin` and its two JLine jars off the classpath and `gimle
top` goes back to being an unknown verb, with nothing else to unwind — no endpoint, no stored
state, no file on disk, no flag another component reads.

`gimle-hugin` is also the only place JLine appears in this codebase. It is there for exactly one
thing the JDK does not offer — putting a terminal into raw mode — and the provider selected by name
is the Foreign Function &amp; Memory one, so there is no JNI and no bundled native library, which is
the same rule the rest of the platform follows.

Named for Odin's raven who flies out and reports back what he sees — sibling to
[Muninn](../architecture/observability.md), who remembers. Muninn stores; Hugin looks, live.
