# Cluster Forge

# Ivaldi — Lovable build brief (initial message)

Build **Ivaldi**, the Gimlé cluster designer, as a plain client-side SPA (Vite + React 19 + TypeScript + TanStack Router file-based routes; NO TanStack Start, NO SSR, NO backend, NO Supabase, NO auth, NO tests). Everything below is the whole v1 scope. Do not add screens or features beyond it.

## 1. What it is

A drag-and-drop canvas where a developer or operator designs a **local** Gimlé cluster: the platform processes (store, control plane, Fafnir, Muninn, Andvari, node agents on one or more machines) together with the application deployed on it (tenants, workloads, services, network policies, config, secrets, limit ranges). Validation problems appear live while drawing. The design is exported as a zip of the exact YAML files Gimlé's own tools consume, or handed to a local runner (mocked in this prototype).

## 2. Stack and architecture (strict)

- `src/routes/` TanStack file routes: `__root.tsx` (app shell), `index.tsx` (Blueprints list), `designer.$blueprintId.tsx` (the Designer). Nothing else.
- Central state in Zustand 5 stores under `src/stores/`:
  - `useBlueprintStore` — the open Blueprint document, selection, dirty flag, 50-step undo/redo, actions: `addNode(kind, position)`, `updateNode(id, patch)`, `removeNode(id)`, `connect(edge)`, `disconnect(edgeId)`, `moveNode(id, position)`, `load(id)`, `save()`, `duplicate()`.
  - `useValidationStore` — `problems[]` recomputed synchronously from the document after every change via `lib/rules`, plus `serverProblems[]` (empty in this prototype), selectors `problemsFor(nodeId)`, `errorCount`, `warningCount`.
  - `useRunStore` — a mocked local run: `status: idle | validating | booting | seeding | running | stopping | failed`, `log[]`, `endpoints` (control plane `http://127.0.0.1:8080/console` etc). `start()` walks the states with timers and appends realistic log lines; `stop()` returns to idle. Refuses to start while `errorCount > 0`.
  - `useBlueprintsListStore` — list/create/delete/duplicate blueprints.
  - `useUiStore` — which drawers are open (Files, Problems, Run), inspector width, theme (`dark` default, persisted).
- Repository interfaces in `src/repositories/contracts.ts`: `BlueprintsRepository` (list/get/save/delete), `RunsRepository` (start/status/stop/log). Implement `LocalStorageBlueprintsRepository` and `MockRunsRepository`. `src/repositories/index.ts` is the single composition root exporting the singletons. Stores import from `@/repositories` only.
- Pure modules in `src/lib/`: `blueprint.ts` (types + factory + sample blueprints), `rules.ts` (`validate(blueprint): Problem[]`), `render.ts` (`renderFiles(blueprint): RenderedFile[]`), `ports.ts` (defaults, conflict detection), `zip.ts` (fflate zip of rendered files, triggers download).
- Canvas: `@xyflow/react`. Palette items are dragged onto the canvas with native HTML5 drag-and-drop (React Flow's documented pattern). Custom node components per kind. Custom edge labels.
- YAML via the `yaml` package. Zip via `fflate`. Icons via lucide-react. Toasts via sonner. shadcn/ui new-york primitives.

## 3. Design system (apply verbatim)

Fonts `Work Sans` (UI) and `JetBrains Mono` (labels, identifiers, numbers) via @fontsource or Google Fonts. Tailwind v4, tokens in `src/styles.css`, `<html class="dark">` default with a working light theme. Put these tokens in `src/styles.css` exactly:

```css
@custom-variant dark (&:is(.dark *));
@theme inline {
  --radius-sm: calc(var(--radius) - 4px); --radius-md: calc(var(--radius) - 2px);
  --radius-lg: var(--radius); --radius-xl: calc(var(--radius) + 4px);
  --font-sans: "Work Sans", ui-sans-serif, system-ui, sans-serif;
  --font-mono: "JetBrains Mono", ui-monospace, SFMono-Regular, Menlo, monospace;
  --color-signal: var(--signal); --color-hud: var(--hud);
  --color-background: var(--background); --color-foreground: var(--foreground);
  --color-card: var(--card); --color-card-foreground: var(--card-foreground);
  --color-popover: var(--popover); --color-popover-foreground: var(--popover-foreground);
  --color-primary: var(--primary); --color-primary-foreground: var(--primary-foreground);
  --color-secondary: var(--secondary); --color-secondary-foreground: var(--secondary-foreground);
  --color-muted: var(--muted); --color-muted-foreground: var(--muted-foreground);
  --color-accent: var(--accent); --color-accent-foreground: var(--accent-foreground);
  --color-destructive: var(--destructive); --color-destructive-foreground: var(--destructive-foreground);
  --color-border: var(--border); --color-input: var(--input); --color-ring: var(--ring);
  --color-status-ok: var(--status-ok); --color-status-warn: var(--status-warn);
  --color-status-bad: var(--status-bad); --color-status-info: var(--status-info);
  --color-status-muted: var(--status-muted);
  --color-status-ok-bg: var(--status-ok-bg); --color-status-warn-bg: var(--status-warn-bg);
  --color-status-bad-bg: var(--status-bad-bg); --color-status-info-bg: var(--status-info-bg);
  --color-sidebar: var(--sidebar); --color-sidebar-foreground: var(--sidebar-foreground);
  --color-sidebar-primary: var(--sidebar-primary); --color-sidebar-primary-foreground: var(--sidebar-primary-foreground);
  --color-sidebar-accent: var(--sidebar-accent); --color-sidebar-accent-foreground: var(--sidebar-accent-foreground);
  --color-sidebar-border: var(--sidebar-border); --color-sidebar-ring: var(--sidebar-ring);
}
/* Light — cool paper with mint signal */
:root {
  --radius: 0.25rem;
  --background: oklch(0.985 0.005 170); --foreground: oklch(0.22 0.03 220);
  --card: oklch(1 0 0); --card-foreground: oklch(0.22 0.03 220);
  --popover: oklch(1 0 0); --popover-foreground: oklch(0.22 0.03 220);
  --primary: oklch(0.55 0.13 172); --primary-foreground: oklch(0.99 0 0);
  --secondary: oklch(0.955 0.015 175); --secondary-foreground: oklch(0.26 0.04 210);
  --muted: oklch(0.955 0.012 180); --muted-foreground: oklch(0.48 0.03 200);
  --accent: oklch(0.93 0.04 172); --accent-foreground: oklch(0.24 0.04 210);
  --destructive: oklch(0.56 0.2 20); --destructive-foreground: oklch(0.99 0 0);
  --border: oklch(0.9 0.015 185); --input: oklch(0.9 0.015 185); --ring: oklch(0.55 0.13 172);
  --signal: oklch(0.52 0.14 168); --hud: oklch(0.45 0.05 205);
  --status-ok: oklch(0.52 0.14 165); --status-warn: oklch(0.62 0.17 70);
  --status-bad: oklch(0.55 0.21 20); --status-info: oklch(0.5 0.09 215); --status-muted: oklch(0.55 0.01 220);
  --status-ok-bg: oklch(0.94 0.05 165); --status-warn-bg: oklch(0.94 0.08 80);
  --status-bad-bg: oklch(0.95 0.05 20); --status-info-bg: oklch(0.94 0.03 215);
  --sidebar: oklch(0.965 0.01 190); --sidebar-foreground: oklch(0.24 0.03 215);
  --sidebar-primary: oklch(0.55 0.13 172); --sidebar-primary-foreground: oklch(0.99 0 0);
  --sidebar-accent: oklch(0.93 0.04 172); --sidebar-accent-foreground: oklch(0.24 0.04 210);
  --sidebar-border: oklch(0.9 0.015 185); --sidebar-ring: oklch(0.55 0.13 172);
}
/* Dark — deep navy instrument panel with mint signal */
.dark {
  --background: oklch(0.2 0.035 248); --foreground: oklch(0.92 0.07 158);
  --card: oklch(0.245 0.035 235); --card-foreground: oklch(0.92 0.07 158);
  --popover: oklch(0.245 0.035 235); --popover-foreground: oklch(0.92 0.07 158);
  --primary: oklch(0.79 0.14 172); --primary-foreground: oklch(0.2 0.035 248);
  --secondary: oklch(0.3 0.04 200); --secondary-foreground: oklch(0.92 0.07 158);
  --muted: oklch(0.27 0.035 220); --muted-foreground: oklch(0.72 0.05 172);
  --accent: oklch(0.33 0.05 180); --accent-foreground: oklch(0.94 0.08 158);
  --destructive: oklch(0.68 0.19 18); --destructive-foreground: oklch(0.2 0.035 248);
  --border: oklch(0.34 0.04 195); --input: oklch(0.34 0.04 195); --ring: oklch(0.79 0.14 172);
  --signal: oklch(0.9 0.16 155); --hud: oklch(0.72 0.09 175);
  --status-ok: oklch(0.82 0.15 165); --status-warn: oklch(0.83 0.16 80);
  --status-bad: oklch(0.7 0.19 18); --status-info: oklch(0.78 0.1 200); --status-muted: oklch(0.6 0.02 220);
  --status-ok-bg: oklch(0.32 0.07 165); --status-warn-bg: oklch(0.33 0.08 80);
  --status-bad-bg: oklch(0.32 0.1 18); --status-info-bg: oklch(0.3 0.05 200);
  --sidebar: oklch(0.17 0.03 250); --sidebar-foreground: oklch(0.88 0.06 160);
  --sidebar-primary: oklch(0.79 0.14 172); --sidebar-primary-foreground: oklch(0.17 0.03 250);
  --sidebar-accent: oklch(0.27 0.04 210); --sidebar-accent-foreground: oklch(0.92 0.07 158);
  --sidebar-border: oklch(0.3 0.035 210); --sidebar-ring: oklch(0.79 0.14 172);
}
@utility hud-label { font-family: var(--font-mono); font-size: 10px; line-height: 1.2; text-transform: uppercase; letter-spacing: 0.18em; font-weight: 700; color: color-mix(in oklab, var(--hud) 85%, transparent); }
@utility hud-panel { border: 1px solid color-mix(in oklab, var(--primary) 18%, transparent); background: color-mix(in oklab, var(--card) 92%, var(--primary) 8%); }
@utility num { font-family: var(--font-mono); font-variant-numeric: tabular-nums; }
```

Dense operator UI: 12px tables, hud-label eyebrows over every panel title, 0.25rem radius, 1px borders, no heroes, no gradients, no emoji, no decorative animation. Validation severity uses the status tokens (bad = error, warn = warning, info = info); the mint primary means "selected/interactive", never "ok".

## 4. Logo

Create `src/components/ivaldi/IvaldiEmblem.tsx`: an inline SVG mark, `viewBox="0 0 32 32"`, stroke `currentColor`, `strokeWidth 2.2`, `strokeLinecap="square"`, `strokeLinejoin="miter"`, no fill, `aria-hidden`. Motif: a blacksmith's anvil seen from the side (horn to the left) with three small nodes linked in a shallow arc above its face, the middle node dropping a short vertical tie onto the anvil, meaning "a cluster being forged". It must stay readable at 16px and 28px, so at most 6 path elements. Export `<IvaldiEmblem size />` and `IVALDI_FAVICON` (a data URI of the same drawing with stroke `#1a8f78`) and use it in `index.html` and the app header. Wordmark beside it: "IVALDI" in JetBrains Mono 700 with 0.18em tracking and, under it, "gimle // cluster designer" as a hud-label.

## 5. Domain model (put in `src/lib/blueprint.ts`)

```ts
type Severity = "error" | "warning" | "info";
interface Problem { code: string; severity: Severity; message: string; nodeId?: string; edgeId?: string; file?: string; }
type PlatformKind = "machine" | "store" | "controlPlane" | "fafnir" | "muninn" | "andvari" | "agent";
type AppKind = "tenant" | "deployment" | "statefulSet" | "daemonSet" | "job" | "cronJob" | "service" | "networkPolicy" | "configEntry" | "secret" | "limitRange";
interface Blueprint { id: string; name: string; version: string; transport: "plaintext" | "mtls"; tlsMaterialDir?: string; runtime: { dataRoot: string; classpath?: string }; nodes: BlueprintNode[]; edges: BlueprintEdge[]; updatedAt: string; }
interface BlueprintNode { id: string; kind: PlatformKind | AppKind; position: { x: number; y: number }; data: NodeData; }
// NodeData per kind:
// machine: { name, host }   // default host 127.0.0.1
// store: { machine, raftPort=9080, clientPort=9091, jvmFlags?: string[] }
// controlPlane|fafnir|muninn|andvari: { machine, port (defaults 8080/9092/9093/9094), jvmFlags? }  fafnir also { keyFile }
// agent: { machine, nodeId, gossipPort=9090, labels: string[] }
// tenant: { id, quota: { maxMemoryBytes, maxCpuMillicores, maxInstances }, isolationPosture?: "OPEN"|"DENY_BY_DEFAULT" }
// deployment|statefulSet|daemonSet|job|cronJob: { name, tenantId?, module: { name, version }, artifact: { source: "registry" } | { source: "jar", path: string },
//   replicas? (deployment/statefulSet), placement?: { antiAffinity?: boolean, requiredLabels?: string[] },
//   autoscale?: { minReplicas, maxReplicas, targetCpuUtilizationPercent } (deployment only),
//   disruption?: { maxUnavailable, maxSurge } (deployment/daemonSet; daemonSet has no maxSurge),
//   activeDeadlineSeconds?, backoffLimit? (job/cronJob), schedule, concurrencyPolicy: "Allow"|"Forbid"|"Replace", suspend? (cronJob),
//   resources: { request: { memory, cpu }, limit: { memory, cpu } }  // what the module's own descriptor declares; used for quota math }
// service: { name, tenantId, port, targetPort, deploymentNames: string[] }
// networkPolicy: { name, tenantId, deploymentNames?: string[], allowedCallerTenantIds?: string[] }
// configEntry: { tenantId, key, value }
// secret: { tenantId, key }            // value is never stored; rendered as ${values.<key>}
// limitRange: { tenantId, min: { memory, cpu }, max: { memory, cpu } }
type EdgeKind = "placedOn" | "belongsTo" | "fronts" | "allowsCaller" | "restricts";
interface BlueprintEdge { id: string; kind: EdgeKind; source: string; target: string; }
// placedOn: store/controlPlane/fafnir/muninn/andvari/agent -> machine
// belongsTo: workload/service/networkPolicy/configEntry/secret/limitRange -> tenant
// fronts: service -> deployment | statefulSet
// allowsCaller: networkPolicy -> tenant (the allowed caller)
// restricts: networkPolicy -> deployment
```

Dragging a role or resource from the palette creates a node with sensible defaults and auto-connects it: a platform role to the only machine if there is exactly one; an app resource to the only tenant if there is exactly one. Connecting an edge of an invalid kind between two nodes is refused with a toast that says why.

## 6. Rendering (`src/lib/render.ts`) — output must be exactly these files

- `topology.yaml` — Hilmir topology: `name`, `transport` (only if mtls), `tls: {materialDir}` (only if mtls), `machines: [{name, host}]`, `runtime: {dataRoot, classpath?}`, `store: {replicas: [{machine, raftPort, clientPort}]}`, `controlPlane: {replicas: [{machine, port}]}`, `fafnir: {keyFile, replicas: [{machine, port}]}`, `muninn`/`andvari` (only if present), `agents: [{machine, nodeId, gossipPort, labels}]`, `jvm: {role: [...]}` (only roles with flags). Omit ports that equal the defaults.
- `manifests/<NN>-<name>.yaml` — one file per workload, numbered in dependency order (providers before consumers is not knowable, so: statefulSets, daemonSets, deployments, jobs, cronJobs). Flat shape, no metadata/spec nesting:
  `apiVersion: v1` + `kind: Deployment` + `name` + `tenantId` (only if set) + `module: {name, version}` + `replicas` + optional `placement`, `autoscale`, `disruption`. For `artifact.source === "jar"` emit NO `apiVersion` and add `artifactPath: <path>` instead (this is the legacy v1alpha1 form). CronJob: `schedule`, `concurrencyPolicy`, `jobTemplate: {module, activeDeadlineSeconds?, backoffLimit?}`. Job: `module`, `activeDeadlineSeconds?`, `backoffLimit?`. DaemonSet: no `replicas`.
- `manifests/<NN>-service-<name>.yaml` — `kind: Service`, `name`, `tenantId`, `deploymentNames`, `port`, `targetPort`.
- `manifests/<NN>-networkpolicy-<name>.yaml` — `kind: NetworkPolicy`, `name`, `tenantId`, `deploymentNames?`, `allowedCallerTenantIds?`.
- `bundle.yaml` — `kind: Bundle`, `name`, `version`, `values: { <secretKey>: "" }`, `tenants: [{id, quota}]`, `config: [{tenant, key, value}]`, `secrets: [{tenant, key, value: "${values.<key>}"}]`, `workloads: [{file: manifests/...}]` in the numbered order.
- `values.example.yaml` — every secret key with an empty value.
- `README.md` — how to run: `hilmir validate -f topology.yaml`, `hilmir up -f topology.yaml --machine <first machine>`, for each jar-sourced workload `gimle artifact push <path> --server 127.0.0.1:<cpPort>`, then `hilmir deploy -f bundle.yaml --values values.yaml --server 127.0.0.1:<cpPort> --wait`, then the console URL `http://127.0.0.1:<cpPort>/console`, and `hilmir down --machine <machine> --data-root <dataRoot>`.
- `ivaldi.blueprint.json` — the Blueprint itself, so a zip can be re-imported.

## 7. Validation rules (`src/lib/rules.ts`) — all synchronous, all with a stable code

Topology (mirror of Hilmir's own validator): `NO_MACHINES` E, `NO_STORE` E, `NO_CONTROL_PLANE` E, `NO_FAFNIR` E, `UNKNOWN_MACHINE` E (role not placed on a machine), `DUPLICATE_MACHINE` E, `DUPLICATE_NODE_ID` E, `PORT_CONFLICT` E (two processes on one machine claim one port; store counts both ports), `REPLICAS_COLOCATED` W on one machine / E on many, `AGENTS_COLOCATED` W / E likewise, `MTLS_NO_MATERIAL_DIR` E, `MTLS_IP_LITERAL_HOST` E, `SINGLE_STORE` W, `STORE_EVEN_REPLICAS` W, `SINGLE_CONTROL_PLANE` W, `NO_AGENTS` W.
Application: `WORKLOAD_NAME_BLANK` E, `WORKLOAD_NAME_DUPLICATE` E (across all workload kinds), `MODULE_COORDINATE_BLANK` E, `TENANT_UNKNOWN` E (belongsTo points at nothing / tenantId names no tenant node), `SERVICE_TARGET_MISSING` E (fronts nothing, or a name that is not a deployment/statefulSet), `SERVICE_PORT_RANGE` E (1..65535), `SERVICE_CROSS_TENANT` E (service and its target in different tenants), `POLICY_ALLOWED_TENANT_UNKNOWN` E, `POLICY_NO_DIRECTION` E (no deploymentNames and no allowedCallerTenantIds), `REPLICAS_NEGATIVE` E, `AUTOSCALE_RANGE` E (max < min, target <= 0), `DISRUPTION_BOTH_ZERO` E, `DAEMONSET_ANTI_AFFINITY` E, `DAEMONSET_MAX_SURGE` E, `CRON_SCHEDULE_INVALID` E (5 fields), `CRON_POLICY_INVALID` E, `RESOURCES_REQUEST_OVER_LIMIT` E, `LIMITRANGE_VIOLATION` E (request outside tenant min/max), `QUOTA_EXCEEDED` E (sum over the tenant of request × instances vs quota: deployment replicas + maxSurge, statefulSet replicas, job 1, daemonSet agent count, cronJob 0), `REQUIRED_LABEL_UNMATCHED` W (no agent carries a required label), `ANTI_AFFINITY_SHORT` W (antiAffinity with replicas > agent count), `NO_ANDVARI_FOR_REGISTRY` W (a registry-sourced workload while no andvari role exists), `JAR_PATH_RELATIVE` W (artifact path not absolute), `SECRET_NO_VALUE_AT_RUN` I (secrets get values only at run time).
Memory strings are `<n>Mi|Gi`, cpu strings `<n>m` or `<n>`; parse them in `lib/units.ts`.

## 8. Screens

**Blueprints list (`/`)**: dense table (name, version, machines, roles, workloads, problems as three counters, updated), "New blueprint" (creates a minimal valid single-machine blueprint: one machine 127.0.0.1, store, controlPlane, fafnir, one agent `node-1`), "Import zip/JSON", duplicate, delete.

**Designer (`/designer/$blueprintId`)**: a working-canvas layout, never a document:
- Top bar: emblem + blueprint name (inline-editable) + version; right side: problem counters (error/warning/info chips that open the Problems drawer), buttons `Validate`, `Files`, `Download zip`, `Run locally`, theme toggle.
- Left palette (240px): two sections with hud-label headers, "Platform" and "Application", each item a draggable chip with a lucide icon and a one-line hint; a search box on top.
- Center: React Flow canvas with a dot grid, minimap, fit-view control. Machines render as large dashed containers (group nodes); roles placed on a machine render inside it. Application nodes render as compact cards: kind eyebrow, name, one key fact (replicas / port / schedule / quota) and a severity stripe on the left edge when the node has problems. Edge labels: "placed on", "belongs to", "fronts", "allows caller", "restricts". Selecting a node highlights its problems.
- Right inspector (360px): a form for the selected node's data, per kind, using shadcn inputs; every field shows its problem inline under it; a "Delete" at the bottom; when nothing is selected show blueprint-level settings (name, version, transport, tls material dir, data root).
- Bottom drawers (resizable, one at a time): **Problems** (table: severity, code, message, target; clicking selects the node), **Files** (left: file tree of rendered files; right: read-only mono viewer with line numbers, "Copy" per file, live updates as the canvas changes), **Run** (status pill per state, endpoint links when running, a mono log tail; Start disabled while errors exist with the reason shown).

## 9. Sample data (`src/lib/samples.ts`), seeded on first load

1. `orders-platform-local`: one machine `local` (127.0.0.1), store, controlPlane, fafnir (keyFile `~/.gimle/ivaldi/fafnir.key`), muninn, andvari, agents `node-1` (labels: `ssd`) and `node-2`; tenant `orders-platform` (quota 1 GiB, 4000 millicores, 20 instances) with limitRange 32Mi–512Mi; deployment `web-ui-deployment` (module `com.example.webui` 1.1.1, registry, 2 replicas, antiAffinity, request 64Mi/50m limit 256Mi/500m), statefulSet `inventory-service-statefulset` (`com.example.inventory` 1.0.0, 1 replica), cronJob `orders-report-cronjob` (`com.example.reporting` 1.0.0, `*/5 * * * *`, Forbid, activeDeadlineSeconds 60), service `web-ui` (port 80 → targetPort 8090, fronts web-ui-deployment), networkPolicy `web-ui-deny-cross-tenant` (restricts web-ui-deployment, no allowed callers), configEntry `greeting.prefix=Hello`, secret `admin.token`. This one validates clean except one warning: `SINGLE_STORE`.
2. `broken-example`: deliberately wrong — two control planes on one machine on the same port, a service fronting a missing deployment, a deployment with request > limit, mtls with an IP-literal host, so the Problems drawer has content on first open.

## 10. Do not

No tests, no Storybook, no Supabase, no auth, no landing page, no onboarding tour, no extra routes, no analytics, no emoji, no gradients, no `@lovable.dev/vite-tanstack-config`, no server functions. Keep files small and typed; no `any`.

---

# Follow-up prompts (send one at a time, only after reviewing the previous diff)

**Prompt 2 — inspector completeness.** "Finish the inspector forms so every field of every NodeData kind in `lib/blueprint.ts` is editable, with unit-aware inputs for memory/cpu (Mi/Gi, m), a cron field with a 5-field hint, list editors for labels/deploymentNames/allowedCallerTenantIds, and inline problems under each field. No new screens."

**Prompt 3 — files and zip fidelity.** "Make `lib/render.ts` output match section 6 exactly, byte for byte stable for the same blueprint (sorted keys where order is not semantic, 2-space YAML, no trailing spaces). The Files drawer must show every file; `Download zip` must produce `<blueprint-name>.zip` with the same file tree. Add `Import` on the list screen that accepts a zip or `ivaldi.blueprint.json` and reopens the design."

**Prompt 4 — run panel realism.** "In `MockRunsRepository`, model the real boot order (store → muninn → andvari → fafnir → controlPlane → agents → artifact push per jar workload → bundle deploy → wait for ACTIVE) with one log line per step and per-process readiness; expose `endpoints` with the console URL per role. In the Run drawer show a per-process readiness list and links. Keep it a mock; no fetch calls."

**Prompt 5 — polish pass (last).** "Keyboard: Delete removes selection, Cmd/Ctrl+Z / Shift+Z undo/redo, Cmd/Ctrl+S saves. Empty states for canvas and drawers. Light theme audit: every color from tokens. Remove any unused component, dependency, or route."

This project was built with [Lovable](https://lovable.dev).

## Build with Lovable

Continue developing this project in the [Lovable editor](https://lovable.dev/projects/67bde398-ceac-437b-a5a6-a3ec30c3a4a8).

- **Ship faster**: describe what you want to build and Lovable handles the code.
- **Stay in sync**: every change made in Lovable is committed straight to this repository.
- **Full ownership**: this code is yours. Push to `main` on GitHub and your changes sync back into Lovable, ready for your next prompt.

## Development

Prefer working locally? You need Node.js and npm — [install with nvm](https://github.com/nvm-sh/nvm#installing-and-updating).

```sh
git clone <this-repository-url>
cd <repository-name>
npm i
npm run dev
```
