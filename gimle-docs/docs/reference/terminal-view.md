---
sidebar_position: 9
---

# Terminal cluster view (`gimle top`)

A live, read-only dashboard of nodes and instances, in the terminal you are already in. Where
`gimle get nodes` and `gimle get deployments` each answer a question once, `gimle top` keeps
answering it — which is what you want while watching a change settle.

```bash
gimle top --server 127.0.0.1:8080
gimle top --interval=10 --server 127.0.0.1:8080
```

It refreshes every two seconds by default (`--interval=SECS`, 1 to 60), needs an interactive terminal, and quits on `q` leaving the
terminal exactly as it found it.

## What it shows

**Cluster view** — the default screen:

- A status line: the control-plane address, whether the last poll succeeded, node and instance
  counts, and an ok / warn / bad instance split that always sums to the instance count.
- A node table: state (`READY`, `CORDONED`, `STALE`, `UNKNOWN`), CPU and memory against capacity
  with a bar each, how many instances are placed there, and heartbeat age.
- An instance table: workload, kind, index, node, lifecycle state, readiness, request rate, error
  rate, queue depth, memory and CPU. Deployments, DaemonSets, StatefulSets and live Job runs share
  one flat table rather than four grouped blocks — the `KIND` column tells them apart, and it is
  dropped below 100 columns so the workload name keeps the width instead. `o` cycles the ordering
  through name, state and each metric; every metric sorts worst-first, because the reason to sort
  by one is to put the worst instance on the first row.
- A `NOT SETTLED` block, drawn only when something is: any workload short of the replicas it asked
  for, over its tenant's quota, or rejected by a LimitRange, with the reason the control plane
  gives. A healthy cluster shows nothing here at all.

An instance the scheduler has placed but whose node has not reported on yet reads `PENDING` with
every metric shown as `—`. That is deliberate: a zero there would look like a running instance
doing nothing.

Replicas the scheduler placed *nowhere* have no row to appear in, so the status line carries an
`unplaced N` count of its own — without it, a deployment asking for four and running two would look
like a healthy pair.

**Node view** — `tab` moves the cursor to the node table, then `⏎`:

- The node's state and heartbeat age, its CPU and memory against capacity, the isolation tiers it
  accepts, its labels and its taints — a taint being the reason a node is skipped for a tenant it
  would otherwise fit.
- Every instance currently placed there.
- It costs no reads of its own: every field is already in the `GET /nodes` response the cluster
  view polls, so it cannot fail separately from the view that opened it.

Only the focused table shows a cursor, so it is never ambiguous which one `⏎` will act on.

**Instance view** — `⏎` on a selected row:

- The instance's state, liveness, readiness, module coordinate and worker id.
- Its measured request rate, error rate, queue depth, memory and CPU, plus any ports it reported
  for itself and its volume usage. Those last two get a line only when the instance reports them —
  a module that answers only over the fabric has neither.
- Its recent lifecycle transitions, from the same timeline `gimle events` prints.
- A live tail of its own logs, seeded with recent backlog so a quiet instance still shows the lines
  that explain how it got here. `c` switches between the `APPLICATION` and `PLATFORM` categories.

**Services view** — `s` from the cluster view:

- Every declared Service: the deployments it fronts, its port and target port, protocol, and how
  many live endpoints it currently resolves to.
- A Service resolving to no endpoints reads `NO ENDPOINTS` in the same colour a failed instance
  does — that is a real misconfiguration, usually a Service naming deployments that do not exist or
  whose instances are all down. A Service whose endpoints could not be read at all reads `UNKNOWN`
  instead: an unreadable answer is never reported as zero, because zero is the finding.
- Resolving endpoints costs one request per Service, so this screen polls only while it is open.

## Keys

| Key | Does |
| --- | --- |
| `↑` `↓` / `j` `k` | move the selection |
| `⏎` | inspect whatever the cursor is on |
| `tab` | move the cursor between the node and instance tables |
| `o` | cycle the sort: name, state, then each metric worst-first |
| `s` | services and the endpoints they resolve to |
| `esc` | back to the cluster view |
| `/` | filter; `enter` applies, `esc` clears |
| `p` | pause / resume refresh |
| `r` | refresh now |
| `c` | cycle the log category (instance view) |
| `g` / `G` | jump to the top / bottom |
| `?` | help |
| `q` / `ctrl-c` | quit, restoring the terminal |

The filter matches across workload name, instance index, node, lifecycle state, workload kind,
tenant and module coordinate, so typing what you remember tends to be enough.

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
- **A CronJob never appears as itself.** It runs nothing directly — each firing materializes an
  ordinary Job — so its firings appear in the table as Jobs. A Job's live run does appear, keyed by
  its attempt, which is the same wire field as an instance's index; a run that has already finished
  is left out rather than drawn as one still waiting to start.
- **A DaemonSet never reads as short of replicas.** Its desired count is "one per eligible node",
  which the control plane does not compute and therefore does not serve — so a DaemonSet missing
  from a node is not something this view can report, and it does not invent a number to imply
  otherwise.
- **A limit reads differently per tier.** The instance view shows the declared isolation tier and
  resource limit, and draws measured memory against that limit only for `TIER_2`, where the
  instance has a dedicated worker JVM started with that figure as its own `-Xmx`. A `TIER_1`
  instance shares one heap with every other instance on its worker, so the same figure is labelled
  an admission bound and gets no bar — a gauge there would claim headroom this instance does not
  individually have.
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
