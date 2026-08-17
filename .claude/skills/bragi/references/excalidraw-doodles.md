# Excalidraw doodles & doodle videos

Excalidraw (MIT) gives the hand-drawn "whiteboard" aesthetic; its scene format is plain JSON, so
Claude can author and edit `.excalidraw` files directly in the repo. Two open-source animators
turn a scene into motion:

| Tool | License | What it does | Export | Headless? |
|---|---|---|---|---|
| **excalidraw-animate** (dai-shi) | MIT | Draws each element in stroke-by-stroke, in element/group order | Animated SVG, WebM | Browser-based (web app; also an npm package) |
| **excalimate** | MIT | Full keyframe timeline: camera moves, sequenced reveals, per-element keyframes | MP4, WebM, GIF, animated SVG, Lottie | Browser-based app + **MCP server** for AI-driven authoring |

Both export pipelines run in a browser. That's fine: authoring is file-based (Claude writes the
JSON), and export is either a one-click step for the user or scriptable with the preinstalled
Chromium/Playwright if full automation is genuinely needed. Be upfront in your summary about
which step, if any, needs the user's click.

## Authoring `.excalidraw` scenes directly

A scene file is JSON (`assets/templates/doodle.excalidraw` is a minimal valid example):

```json
{
  "type": "excalidraw",
  "version": 2,
  "source": "gimle-docs",
  "elements": [ ... ],
  "appState": { "viewBackgroundColor": "transparent" },
  "files": {}
}
```

Element notes that matter in practice:

- Common fields: `id`, `type` (`rectangle`, `ellipse`, `diamond`, `arrow`, `line`, `text`,
  `draw`), `x`, `y`, `width`, `height`, `angle`, `strokeColor`, `backgroundColor`,
  `fillStyle` (`hachure` is the doodle look), `strokeWidth`, `roughness` (1–2 for hand-drawn),
  `opacity`, `seed` (any integer; vary it per element or every stroke wobbles identically),
  `groupIds`, `isDeleted: false`.
- `text` elements need `text`, `fontSize`, `fontFamily` (`1` = the hand-drawn Virgil font — use
  it; `3` = code font for literals), `textAlign`, `verticalAlign`, and `originalText`.
- `arrow`/`line` use relative `points: [[0,0],[dx,dy],…]`; arrowheads via
  `endArrowhead: "arrow"`.
- **Animation order follows element order** in excalidraw-animate (grouped elements animate
  together). Order the `elements` array in narrative order — that *is* the storyboard.
- House colors: stroke `#1b1b1d` (light-neutral ink), accent `#2a5a8c`. Keep
  `viewBackgroundColor` transparent so the export sits on the page background.

Iterating visually: the user can open the file at <https://excalidraw.com> (File → Open) or with
the VS Code Excalidraw extension, tweak, and save back — the JSON round-trips.

## Path A — excalidraw-animate (quick doodle reveal)

Best for: "draw this diagram before the reader's eyes" with zero timeline work.

1. Author/commit the scene under `gimle-docs/diagrams/<name>.excalidraw`.
2. Open <https://dai-shi.github.io/excalidraw-animate/> and load the file (it also accepts a URL
   to a raw file).
3. Export **animated SVG** (preferred — small, theme-friendly on transparent background) into
   `gimle-docs/static/diagrams/`, or WebM into `gimle-docs/static/video/` (their README notes
   WebM export is imperfect; if it glitches, export SVG and screen-record, or use excalimate).

## Path B — excalimate (keyframed doodle video, Claude-drivable)

Best for: real doodle *videos* — camera pans, staged reveals, narration-ready pacing. It ships an
MCP server (~35 tools, e.g. `auto_animate`, `create_camera_move`) with a live-preview app, so
Claude Code can build the animation itself while the user watches:

```bash
npx @excalimate/mcp-server            # starts on :3001
claude mcp add excalimate http://localhost:3001/mcp
```

Then drive it conversationally (import the committed `.excalidraw` scene, sequence reveals, add
camera moves), and export MP4/WebM/GIF/animated SVG from the app. Commit the export to
`gimle-docs/static/video/` (or `static/diagrams/` for SVG) alongside the source scene.

Only suggest setting up the MCP server when the task is genuinely a keyframed video; for a plain
draw-on reveal, Path A is less machinery.

## Embedding

Animated SVG embeds like any diagram (`<img src="/diagrams/….svg" alt="…" width="720" />`).
WebM/MP4 use the `DocVideo` component — short doodles want `loop autoPlay`; see
`references/tutorial-videos.md` for budgets, posters, and a11y.
