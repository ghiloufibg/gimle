---
name: bragi
description: >
  Create engaging visual media for the Gimlé documentation site (gimle-docs): animated
  architecture and flow diagrams (D2 steps/scenarios rendered to self-contained animated SVG,
  hand-drawn Excalidraw doodles animated via excalidraw-animate or excalimate), scripted terminal
  screencasts of gimle-cli workflows (VHS), and short educational/explainer videos (Motion Canvas,
  Revideo) — all open-source tools, pre-rendered into static assets and embedded in Docusaurus
  MDX pages. Use this skill whenever the user wants a diagram, illustration, animation, GIF,
  screencast, doodle video, tutorial video, or simply wants a gimle-docs page to be more visual,
  engaging, or explanatory — even if no specific tool is named. Also use it when asked to update,
  restyle, or re-render existing media under gimle-docs/diagrams or gimle-docs/static.
---

# Bragi — animated diagrams & tutorial videos for gimle-docs

Named for the Norse god of poetry and eloquence: this skill turns dry architecture prose into
diagrams that move and tutorials you can watch. Everything is produced with open-source tools,
authored as text/JSON sources Claude can write directly, and pre-rendered into static assets so
`gimle-docs`' build (`bun run build` / `mvn -P docs -pl gimle-docs install`) gains **zero new
dependencies**.

## Choosing the medium

Pick the lightest medium that teaches the concept. Motion must explain something (a sequence, a
flow, a state change) — never decorate.

| You want to show | Use | Output | Reference |
|---|---|---|---|
| Architecture / topology / flow, step-by-step build-up | **D2** with `steps:`/`scenarios:` | Animated SVG (self-contained CSS keyframes) | `references/d2-diagrams.md` |
| A static diagram with just a little life | **D2** single board + `style.animated` connections | SVG | `references/d2-diagrams.md` |
| Hand-drawn "whiteboard" feel, doodle video | **Excalidraw** JSON + excalidraw-animate / excalimate | Animated SVG or WebM | `references/excalidraw-doodles.md` |
| A CLI workflow (`gimle deploy …`, cluster bring-up) | **VHS** `.tape` script | WebM/GIF/MP4 | `references/tutorial-videos.md` |
| A narrated/animated explainer (code + motion graphics) | **Motion Canvas** (interactive) or **Revideo** (headless) | MP4/WebM | `references/tutorial-videos.md` |
| A quick inline sketch not worth an asset | Mermaid (already enabled via `@docusaurus/theme-mermaid`) | inline | — |

Mermaid stays the default for trivial state/sequence sketches — don't migrate existing Mermaid
blocks to D2 unless the page genuinely benefits from animation or richer layout.

Licensing note (why this exact toolset): D2 is MPL-2.0; Excalidraw, excalidraw-animate,
excalimate, VHS, Motion Canvas, and Revideo are MIT. **Remotion is deliberately excluded** — it is
source-available with a per-company commercial license, not open source.

## Repository conventions

- **Sources are committed**, next to nothing else, under `gimle-docs/diagrams/`:
  - `gimle-docs/diagrams/*.d2` — D2 sources
  - `gimle-docs/diagrams/*.excalidraw` — Excalidraw scenes (JSON)
  - `gimle-docs/diagrams/*.tape` — VHS scripts
  - larger video projects (Motion Canvas/Revideo) get their own subdirectory with a README
- **Rendered outputs are committed too**, as plain static assets (the docs build must never need
  d2/vhs/ffmpeg installed): animated/static SVGs → `gimle-docs/static/diagrams/`, videos and
  posters → `gimle-docs/static/video/`.
- **Name assets after the doc page they serve**: `module-lifecycle-states.d2` →
  `/diagrams/module-lifecycle-states.svg`, used by `docs/reference/module-lifecycle.md`.
- **Size budgets**: SVG ≤ 300 KB; WebM ≤ 5 MB (target 720–1080p, VP9); GIF only for tiny loops
  ≤ 2 MB — otherwise convert to WebM. Re-encode rather than commit anything larger.
- Rendered SVG/video files are binary-ish generated assets, but they are *deliberate* committed
  assets (same status as `static/img/logo.png`), not repo-hygiene violations — keep source and
  output in the same commit so they never drift.

## House style

Match the site's identity (`gimle-docs/src/css/custom.css`, same system as gimle-console):

- **Fonts**: Work Sans (labels/prose), JetBrains Mono (code, terminal).
- **Accent**: `#2a5a8c` (light mode primary) / `#6ea8dc` (dark mode primary). Use the accent for
  the *one thing the viewer should look at*; keep everything else neutral.
- **D2**: always `--sketch` (hand-drawn look, pairs with Excalidraw's aesthetic), `--theme=0`
  light + `--dark-theme=200` so one SVG adapts to Docusaurus's color mode automatically.
- **Terminal (VHS)**: JetBrains Mono, dark background `#1b1b1d` (Docusaurus dark surface),
  padding 24+, no personal prompts/paths in frame.
- **Motion**: 1000–1500 ms per animation step; a whole animated diagram loop should stay under
  ~15 s, a tutorial video under ~90 s. Longer than that → split into chapters.
- **Naming register**: any new named example/component in media follows the repo's Norse line.

## Workflow

1. **Read the target doc page first.** The media must teach that page's actual concept — reuse its
   terminology and its real command lines (copy them, don't invent flags). For architecture
   diagrams, verify component names/roles against `CLAUDE.md` before drawing.
2. **Author the source** in `gimle-docs/diagrams/` (see the per-medium reference file).
3. **Render**:
   - D2: `scripts/render-diagrams.sh` renders every `.d2` source with house-style flags
     (auto-detects multi-board sources and adds `--animate-interval`). It installs the pinned D2
     release via `scripts/install-d2.sh` if `d2` isn't on `PATH`.
   - Excalidraw/VHS/Motion Canvas: follow the reference file — some of these need a browser or
     ffmpeg; the references say what works headless and what needs the user's machine.
4. **Embed in the MDX page** (below), with meaningful `alt`/caption text.
5. **Verify the build**: `cd gimle-docs && bun install && bun run build` (or
   `mvn -P docs -pl gimle-docs install`). `onBrokenLinks: 'throw'` will fail the build on a
   mistyped asset path only for *links*, not `<img>` sources — so also open the built page or the
   SVG itself to confirm the asset renders.
6. **Commit source + rendered output + page edit together**, Conventional Commits style
   (`docs: ...`), max 3 lines, no AI attribution (the `commit-msg` hook rejects it).

## Embedding in Docusaurus MDX

**Animated/static SVG** (light/dark handled inside the SVG itself when rendered with
`--dark-theme`):

```mdx
<img
  src="/diagrams/module-lifecycle-states.svg"
  alt="Module lifecycle: INSTALLED → RESOLVED → STARTING → ACTIVE → STOPPING → UNINSTALLED, animated step by step"
  width="720"
/>
```

**Video** — copy `assets/DocVideo.tsx` to `gimle-docs/src/components/DocVideo/index.tsx` once
(don't re-copy if it already exists), then:

```mdx
import DocVideo from '@site/src/components/DocVideo';

<DocVideo
  src="/video/deploy-first-module.webm"
  poster="/video/deploy-first-module-poster.png"
  caption="Deploying greeter-provider with gimle-cli (45 s)"
/>
```

Short doodle loops: add `loop autoPlay` (the component then also mutes and inlines playback, and
respects `prefers-reduced-motion` by falling back to the poster).

## Accessibility & performance

- Every diagram gets an `alt` that states the takeaway, not "diagram of X".
- Every video gets a `caption`, a `poster` (extract with
  `ffmpeg -i in.webm -frames:v 1 poster.png`), and — for narrated content — a text transcript in a
  collapsed `<details>` below the embed.
- Animated SVGs loop by design; keep loops short and calm (no flashing), and prefer a static
  companion SVG in reference pages where readers need to *study* the final state rather than watch
  it build up. Render both from the same `.d2` when in doubt (the render script does this for
  multi-board sources: `<name>.svg` animated + `<name>-static.svg` final board).

## References

- `references/d2-diagrams.md` — D2 syntax essentials, steps/scenarios/layers, animation flags,
  verified install & render commands, styling with Gimlé tokens.
- `references/excalidraw-doodles.md` — authoring `.excalidraw` JSON directly, animating with
  excalidraw-animate (web/npm) and excalimate (MCP server — plugs straight into Claude Code),
  doodle-video export paths.
- `references/tutorial-videos.md` — VHS tapes for terminal screencasts (including recording
  against a real local Gimlé cluster), Motion Canvas/Revideo explainers, Manim mention, ffmpeg
  recipes, embedding and budgets.
- `assets/templates/` — house-styled starting points: `module-lifecycle.d2` (tested, renders as
  animated SVG), `demo.tape` (VHS, Gimlé terminal theme), `doodle.excalidraw` (minimal valid
  scene).
