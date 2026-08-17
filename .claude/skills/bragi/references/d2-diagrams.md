# D2 diagrams for gimle-docs

D2 (<https://d2lang.com>, MPL-2.0) is a declarative diagram language: you write text, the CLI
lays out and renders SVG. It is the primary tool here because Claude can author and iterate on
the source directly, and because multi-board compositions render to a **self-contained animated
SVG** (CSS `@keyframes` inside the file — no JS, works inside a plain `<img>` tag).

## Install & render (verified commands)

`scripts/install-d2.sh` downloads the pinned release binary from GitHub into `~/.local/bin`
(override dir with `D2_INSTALL_DIR`, version with `D2_VERSION`). Note: the official
`curl https://d2lang.com/install.sh | sh` path can be blocked by egress proxies (403) — the
GitHub `releases/download/` URL works, which is why the script uses it.

Render one file by hand:

```bash
d2 --sketch --theme=0 --dark-theme=200 --pad=20 source.d2 out.svg              # static
d2 --sketch --theme=0 --dark-theme=200 --animate-interval=1200 source.d2 out.svg  # animated (multi-board only)
```

- `--sketch` — hand-drawn aesthetic (house style).
- `--theme=0 --dark-theme=200` — embeds *both* light and dark renderings; the SVG switches on
  `prefers-color-scheme`, so one asset serves both Docusaurus color modes.
- `--animate-interval=<ms>` — required whenever the source has more than one board
  (`steps:`/`scenarios:`/`layers:`); packages all boards into one SVG that cycles, ~1200 ms per
  board fits the house motion style. Only SVG and GIF outputs support this; always prefer SVG.
- `d2 fmt source.d2` — canonical formatting before committing.

Prefer `scripts/render-diagrams.sh` over hand runs: it applies all of the above to every `.d2`
under `gimle-docs/diagrams/`, auto-detects multi-board sources, and also emits a
`<name>-static.svg` of the final `steps` board for reference pages.

## Language essentials

```d2
direction: right          # or down (default), up, left

# Shapes: `id: Label`. Connections: `a -> b: label`.
cp: Control Plane
agent: Node Agent
cp -> agent: assign instance

# Containers nest with dots or blocks:
node: {
  label: "machine-1"
  agent: Node Agent
  worker: Worker JVM
}
cp -> node.agent

# Shape types & styling:
store: gimle-mimir {
  shape: cylinder
  style.fill: "#2a5a8c"
  style.font-color: "#ffffff"
}

# A connection that visibly flows (marching-ants) even in a single static board:
cp -> store: raft replication {style.animated: true}
```

`style.animated: true` on connections is the cheapest way to make a *single-board* diagram feel
alive — use it for the one or two connections that carry the page's point, not everywhere.

Reusable styling — define classes once, apply by name:

```d2
classes: {
  accent: {style: {fill: "#2a5a8c"; font-color: "#ffffff"}}
  ghost: {style: {opacity: 0.35}}
}
active.class: accent
```

## Multi-board animation: steps vs scenarios vs layers

- **`steps:`** — each board *inherits cumulatively from the previous step*. Perfect for build-up
  narratives: deployment flow, lifecycle progression, request path. This is the default choice.
- **`scenarios:`** — each board inherits from the *base* board only. Use for variants of one
  diagram (e.g. "healthy cluster" vs "node lost" vs "rescheduled").
- **`layers:`** — independent boards (drill-downs). Rarely wanted for animation.

Pattern for a lifecycle-style build-up (this exact shape is tested — see
`assets/templates/module-lifecycle.d2`):

```d2
direction: right
steps: {
  1: {
    installed: INSTALLED
  }
  2: {
    installed -> resolved: resolve deps
    resolved: RESOLVED
  }
  3: {
    resolved -> active: start
    active: ACTIVE {style.fill: "#2a5a8c"; style.font-color: "#ffffff"}
  }
}
```

In a step you can also *modify* inherited objects (restyle the now-current state, dim previous
ones with an opacity class) or remove them with `null` (`old-worker: null`). Dimming what came
before and accenting what's new is what makes the animation legible.

## Gimlé-specific guidance

- Component names must match the real topology (Control Plane, Node Agent, Worker JVM,
  gimle-mimir, gimle-fafnir, gimle-muninn, gimle-andvari…) — check `CLAUDE.md`'s "Node topology"
  before drawing; a diagram with an invented component name is a defect.
- The nesting Machine → Node Agent / Worker JVM → Module → Instance is the platform's central
  picture — reuse it as the visual grammar across diagrams so readers recognize it instantly.
- Keep one diagram to one idea. Six boxes with two accents beats twenty boxes with a legend.
- Label edges with *verbs* ("assign instance", "report health"), nodes with *names*.

## Embedding

```mdx
<img
  src="/diagrams/service-fabric-call-path.svg"
  alt="A fabric call preferring same-worker, then same-machine, then remote replicas"
  width="720"
/>
```

Width ~680–760 px fits the doc content column. The SVG scales; `width` just caps it.
