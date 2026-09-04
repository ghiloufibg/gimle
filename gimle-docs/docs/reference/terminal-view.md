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

It refreshes every two seconds by default (`--interval=SECS`, 1 to 60; the services, activity and
resource screens sit behind a five-second floor, since one costs a request per Service and the
others only change as fast as people do), needs an interactive terminal, and quits on `q` leaving
the terminal exactly as it found it.

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
  is to put the worst row first. A digit picks an ordering outright instead of cycling to it, and
  each table's own label names which digits do that. Node utilization is compared as a fraction of
  each node's own capacity, so a small node running hot outranks a large one that is merely busy.
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
  that explain how it got here. `c` switches between the `APPLICATION` and `PLATFORM` categories,
  `w` wraps a line too long for the pane instead of cutting it (off by default — one row per line
  is what makes a tail scannable, and a wrapped stack trace would push everything above it off the
  top), `t` hides the clock column and gives its width to the message,
  and `/` narrows the tail the same way it narrows a table — matched against each line's level and
  message, never its clock, so typing digits to find a message does not also match every line
  logged in that minute. A filter matching nothing says so rather than reading as a quiet instance,
  and `esc` clears the filter before it closes the pane.

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

**One tenant** — `:tenant ID` from any screen, `:tenant all` to undo it:

Gimlé's equivalent of a namespace. Every screen narrows to that tenant's own rows, and the status
bar names the tenant on every screen it is narrowing — without that, a cluster showing one tenant's
three instances is indistinguishable from a cluster that has only three.

- **A view narrowing, never an authorization scope.** The control plane already decided what your
  certificate may see; choosing a tenant here only hides some of what it sent. It is not a way to
  see more, and it is not proof you saw less.
- **Nodes are never narrowed.** A node belongs to the cluster, not to a tenant, and hiding the
  machine a tenant's instances run on would answer "where is this running" with silence.
- **A kind whose rows carry no tenant is left whole** — roles, accounts, kind definitions are
  cluster-wide, and narrowing them would report that none of them exist.
- **The untenanted namespace is a scope of its own**, not a wildcard: `:tenant all` is how you get
  everything back.
- The two paged feeds (audit and lifecycle) send `?tenant=` rather than filtering what came back —
  narrowing a page after it arrives would report a quiet tenant whenever a busier one filled the
  page ahead of it. Everything else arrives whole, so its rows are narrowed exactly.
- A name matching nothing shows nothing. That is the honest answer for a tenant your certificate
  cannot see and for one that was mistyped alike — and since the bar names the scope, an empty
  screen is never a mystery.

**Traces** — `T` in the instance view:

- The spans that instance's worker shipped, grouped into the traces they belong to, newest trace
  first, each trace's spans indented under it oldest first so the chain reads in the order it
  happened. A trace carrying a failed span reads `FAILED`, and they are counted on the label.
- **No elapsed time is shown anywhere.** The shipper records only each span's end instant, so any
  duration here would be invented rather than read.
- A trace whose root span was never shipped — it began in another process, or was trimmed from this
  worker's history — is still listed, named by its own id rather than dropped for want of a heading.
- Shipping to Muninn is optional, so a worker that ships nowhere says exactly that rather than
  reading as one that served nothing. An instance whose worker the agent has not reported yet has
  no history to address, and the pane does not open rather than opening on a guess.

**Another cluster** — `:ctx NAME` from any screen:

- Points the whole view at another control plane, named either by a context `gimle context set`
  stored or by a bare `host:port`. A name matching neither is refused on the spot rather than
  dialled — a typo dialled as a hostname fails later, further away, and far less clearly.
- Everything open is closed first, the tenant scope included — an id means nothing on a cluster
  that has never heard of it. Every screen here is about one cluster, and a drill-down, a
  service table or a browsed kind carried across would be describing the previous cluster under the
  new one's name. The kind catalog goes with them: which kinds exist is that cluster's own answer.
- A stored context holds an endpoint and never a credential, so the client certificate and CA stay
  whatever `gimle.tls.*` points at. That is right for another replica of the same cluster; a
  cluster under a different PKI simply fails to connect rather than misleading anyone.

**Services view** — `s` from the cluster view:

- Every declared Service: the deployments it fronts, its port and target port, protocol, and how
  many live endpoints it currently resolves to.
- A Service resolving to no endpoints reads `NO ENDPOINTS` in the same colour a failed instance
  does — that is a real misconfiguration, usually a Service naming deployments that do not exist or
  whose instances are all down. A Service whose endpoints could not be read at all reads `UNKNOWN`
  instead: an unreadable answer is never reported as zero, because zero is the finding.
- Resolving endpoints costs one request per Service, so this screen polls only while it is open.

**XRay** — `x` from the cluster view:

The chain a call actually travels — Service → the deployments it fronts → their instances — as one
tree. Both halves are already drawn elsewhere; what is only visible here is the gap between them.

- A Service naming a workload the cluster has never heard of reads `NOT FOUND`; one naming a
  workload that exists but is running nothing reads `NOT RUNNING`. Those are two different mistakes
  — a Service pointed at a typo, against a workload scaled to zero — and telling them apart is most
  of the value of looking.
- Workloads no Service fronts get their own heading. Nothing can reach them except whatever already
  knows their instances, which is a finding about the cluster's wiring, not an absence of data.
- A Service and a workload in different tenants are never joined to each other, so two tenants
  running an identically-named deployment are never reported as one fronting the other's instances.
- `/` narrows the tree and keeps every matched row's ancestors, so a matched instance still appears
  under the Service and deployment it belongs to.
- It costs exactly what the services screen costs — one request per declared Service — because it is
  a join of two readings rather than a read of its own.

**Scan** — `S` from the cluster view, or `:scan`:

Everything wrong with the cluster, worst first, on one screen. Nothing here is a reading the other
screens could not be made to show; what it adds is that an unplaced replica, a Service resolving to
nothing and a node that stopped heartbeating are three tables apart and one problem.

- `ERROR` is something not running that was asked to run, or unreachable: replicas the scheduler
  placed nowhere, an instance `FAILED` or failing its liveness probe, a node whose agent has stopped
  reporting, a Service resolving to no endpoint, a Service fronting a workload this cluster does not
  have.
- `WARNING` is degraded or heading that way: an instance active but not ready, one placed on a node
  that has reported nothing about it yet, a workload over its tenant's quota or outside its limit
  range, a node committed to almost all of its own cpu or memory. A node close to full is said
  before placement actually fails, because by then the finding is a workload's unplaced replicas and
  no longer names the machine that ran out.
- `NOTE` is deliberate and only looks like a fault from a distance: a cordoned node, a workload
  scaled to zero.
- An instance still starting is never reported for being unready — it is unready by design, and
  reporting it would fill the screen with findings that resolve themselves.
- A check whose input is missing is never silently skipped. If the Services read has not landed, the
  scan says so as a finding of its own: a clean result that came back clean because half the checks
  never ran is worse than no scan.
- A filter matching nothing says `nothing matches`, never the clean-cluster wording — the two read
  identically as an empty table and only one of them is good news.
- It makes no request of its own beyond the Services read the services screen also costs.

**Permissions** — `R` from the cluster view, or `:can`:

What your own certificate may do, as a grid of every resource kind against every verb. The one
cluster fact no other screen can show: roles, bindings and accounts are all browsable as tables, but
reading a grant out of them is the authorizer's job, and three tables plus mental arithmetic is not
an answer to "may I delete this".

- Every cell is the control plane's own answer from `GET /authz/can-i`, not a verdict computed here
  from the RBAC objects — a second implementation of the authorizer could disagree with the real one.
- The kinds and verbs come from `GET /authz/vocabulary`, so a kind added to the platform after this
  view was written still appears.
- A cell the control plane did not answer reads `unknown`, never `no`. Denial and silence are
  indistinguishable once drawn, and only one of them is a statement about anybody's grants; the
  label counts the unanswered cells so a partial read is visible.
- Over a plaintext transport there is no client certificate to identify anyone, so the control plane
  answers as an unidentified caller and allows everything. The screen says so in place of the grid's
  meaning: an unbroken column of `yes` is exactly what an over-privileged account would also produce.
- It is one request per cell, so it is read once when opened and again only on `r` — a grant arrives
  by someone editing a Role, which is not a thing that happens while you are reading the screen about
  it. Changing `:tenant` while it is open re-asks it, since the answer differs per tenant.

**Pulse** — `P` from the cluster view:

One screen answering "is this cluster all right", from the two readings that together say so.

- The control plane's own account of itself: up or down, uptime, transport, how many tenants it can
  still see in the store. A control plane that has lost its store answers every list route from
  nothing, so the cluster view alone would look serene.
- What it is running: nodes ready, instances failed and not ready, replicas unplaced, workloads
  unsettled. A healthy control plane says nothing about instances crash-looping under it.
- Deployments currently reporting errors, named before the merely busy ones.
- A control plane that never answered reads `UNREACHABLE`, distinct from one reporting itself
  `DOWN` — the second is a process reporting on itself, the first is no process reporting anything.
- The traffic rollup is gated on its own permission; a caller who cannot read it is told that, not
  shown a cluster serving nothing.

**Resource browser** — `:` from any table, then a kind:

Every collection the control plane lists, in one table whose columns come from the kind itself
rather than from a layout written per kind. That is also what lets a kind this cluster registered
after the view was written appear here at all.

| `:` | Shows |
| --- | --- |
| `deployments` `daemonsets` `statefulsets` `jobs` | the workload *as itself* — its declared replica count, module coordinate and how many replicas went unplaced, none of which is readable from a table of the instances it happens to be running |
| `services` `alertrules` | the same declarations the `s` and activity screens show, browsable and describable |
| `tenants` | id, isolation posture, live instance count against the quota's own maximum |
| `cronjobs` | a CronJob *as itself* — its schedule, whether it is suspended, its concurrency policy |
| `limitranges` | the per-tenant request/limit guardrails |
| `networkpolicies` | which caller tenants a policy admits, and the deployments it is scoped to |
| `ingresses` | the host each fronts and how many routes it declares |
| `roles` `rolebindings` `accounts` | the RBAC record: who holds what, and under which role |
| `volumes` | every allocated volume, its owning StatefulSet instance, node, and whether in use |
| `kinddefinitions` | the custom kinds this cluster has registered |
| *a registered kind* | reached by its plural, its kind name, or any short name it declared |

A registered kind's columns are the print columns its own definition declares, after the name and
tenant every custom resource carries — so what shows is what whoever registered the kind chose to
surface, and the label says `registered kind` because two clusters can legitimately show the same
kind differently. Nothing in this table is coloured: these are fields whose meaning the view does
not know, and painting one would be inventing a judgement.

Each kind is gated on its own permission, and a caller lacking one is told that rather than shown
an empty table — an empty table reads as "this cluster has no tenants", which is a different and
much more alarming claim. A mistyped kind is answered on the spot with the keys that share what was
typed, not by the screen silently not changing.

Two collections are absent, and both because of the API rather than a choice made here: ConfigMaps
and secrets are addressable only one name at a time, with no route to list them; and the artifact
catalog answers with bare module-id strings rather than objects, so it has no columns to draw.

Pressing `:` and then `enter` with nothing typed lists every kind instead of failing to name one —
including the kinds this particular cluster registered, which is the part no documentation can
carry, since they differ per cluster.

**Describe** — `⏎` on a row in the resource browser, or `d` on an instance row in the cluster view:

- The whole object the collection route answered with, as YAML, scrollable with `↑↓` and `g`/`G`.
  The fields no column had room for are the reason this pane exists.
- It re-reads nothing. The row already carries the object it was drawn from, so the table and the
  detail can never disagree about which read is current.
- It is a rendering, not a manifest: it carries the status the control plane computes alongside the
  spec that was submitted, and feeding it back to `gimle apply` is not something it promises.
- `d` in the cluster view opens it on the workload behind the selected instance — the same browser
  and the same pane, addressed by name rather than by cursor, so there is one path to a described
  resource rather than two that could disagree.

## Keys

| Key | Does |
| --- | --- |
| `↑` `↓` / `j` `k` | move the selection |
| `⏎` | inspect whatever the cursor is on; describe it in the resource browser |
| `tab` | move the cursor between the node and instance tables |
| `o` | cycle the sort: name, state, then each metric worst-first |
| `1` … `9` | sort by that column outright, on whichever table has the cursor |
| `d` | describe the workload behind the selected instance |
| `S` | scan: everything wrong with the cluster, worst first |
| `R` | what your own certificate may do, kind by kind |
| `s` | services and the endpoints they resolve to |
| `x` | the dependency tree: service → deployment → instance |
| `P` | one-screen health: the control plane and what it runs |
| `a` | cluster activity; `c` switches audit / lifecycle / alerts |
| `:` | open a kind: `tenants`, `roles`, `volumes`, a registered kind… |
| `:` then `enter` | list every kind this cluster can show |
| `:ctx NAME` | point at another control plane, by context name or `host:port` |
| `:tenant ID` | narrow every screen to one tenant; `:tenant all` undoes it |
| `:scan` / `:can` | the same two screens `S` and `R` open, by name |
| `T` | this instance's shipped traces (instance view) |
| `w` / `t` | wrap long log lines / show the clock column (instance view) |
| `m` | load older entries (activity view, audit and lifecycle feeds) |
| `esc` | back to the cluster view |
| `/` | filter; `enter` applies, `esc` clears — tables and the log tail alike |
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
- **A CronJob never appears in the *instance* table as itself.** It runs nothing directly — each
  firing materializes an ordinary Job — so its firings appear there as Jobs. A Job's live run does
  appear, keyed by its attempt, which is the same wire field as an instance's index; a run that has
  already finished is left out rather than drawn as one still waiting to start. The CronJob itself
  — its schedule, whether it is suspended — is a declaration rather than something running, and is
  read under `:cronjobs`.
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
- **One cluster at a time.** `:ctx` repoints the view, but nothing is ever shown side by side, and
  the identity presented is always this process's own — switching endpoint does not switch
  credentials.
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
