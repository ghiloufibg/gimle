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

It refreshes every two seconds by default (`--interval=SECS`, 1 to 60; the services and activity
screens sit behind a five-second floor, since one costs a request per Service and the other only
changes as fast as people do), needs an interactive terminal, and quits on `q` leaving the
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
  of whichever table the cursor is on — name/state/each metric for instances, id/cpu/memory/
  instances/heartbeat for nodes; every measure sorts worst-first, because the reason to sort by one
  is to put the worst row first. Node utilization is compared as a fraction of each node's own
  capacity, so a small node running hot outranks a large one that is merely busy.
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

**Activity view** — `a` from the cluster view, `c` to switch feed:

Three records of what is going on, sharing one table because they read the same shape. The label
always names which one is showing — they answer different questions, and a feed mistaken for
another would silently omit exactly what was being looked for.

- **audit** — authorization decisions: who asked, of what, and whether it was allowed. A decision
  refused for want of permission reads `DENIED`; one refused on its merits reads `REJECTED`.
- **lifecycle** — instance transitions across every workload, from the cluster-wide events read.
  This is the same record the drill-down shows for one instance, without having to pick one first.
- **alerts** — declared alert rules and whether each is currently firing. Firing rules sort first.
  A disabled rule says so without being asked; a rule the control plane has no reading for yet
  reads `UNKNOWN` rather than being reported as quiet.

`/` filters, `m` loads older entries on the two paged feeds, and the status line counts the rows
worth finding — refusals, or firing rules. Each feed is gated on a permission of its own; a caller
whose certificate lacks one is told exactly that, because an empty feed would read as a quiet
cluster. Like the services view this polls only while open — the alert feed additionally costs one
request per declared rule, since the rule list carries no firing state of its own.

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
| `a` | cluster activity; `c` switches audit / lifecycle / alerts |
| `m` | load older entries (activity view, audit and lifecycle feeds) |
| `esc` | back to the cluster view |
| `/` | filter; `enter` applies, `esc` clears |
| `p` | pause / resume refresh |
| `r` | refresh now |
| `c` | cycle the log category (instance view) |
| `g` / `G` | jump to the top / bottom |
| `?` | help |
| `q` / `ctrl-c` | quit, restoring the terminal |

The filter matches across workload name, instance index, node, lifecycle state, workload kind,
tenant and module coordinate, so typing what you remember tends to be enough. It is one filter
shared by every screen that has rows to narrow — type it once and it applies wherever you go.

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
- **A DaemonSet's shortfall comes from the control plane, not from arithmetic here.** It declares
  no `replicas` of its own — its desired count is one per eligible node, which only the scheduler
  can work out — so the view reads the `desired` count the control plane publishes. A workload
  carrying neither reports nothing to be short of rather than guessing.
- **A limit reads differently per tier.** The instance view shows the declared isolation tier and
  resource limit, and draws measured memory against that limit only for `TIER_2`, where the
  instance has a dedicated worker JVM started with that figure as its own `-Xmx`. A `TIER_1`
  instance shares one heap with every other instance on its worker, so the same figure is labelled
  an admission bound and gets no bar — a gauge there would claim headroom this instance does not
  individually have.
- **One cluster.** No context switching; point `--server`/`GIMLE_SERVER` at the one you want.
- **Mouse: the wheel only.** A wheel notch moves the cursor, the same as an arrow key. There is no
  click-to-select: the screens hand back a list of strings with no record of which row landed on
  which line, and giving them one purely to serve a click is a worse trade than not having clicks.

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
