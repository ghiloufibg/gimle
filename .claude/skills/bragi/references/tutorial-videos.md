# Short tutorial & educational videos

Three open-source routes, by content type. (Remotion is excluded on licensing grounds — it
requires a per-company commercial license. Motion Canvas and Revideo are MIT; VHS is MIT;
Manim CE is MIT.)

## Route 1 — VHS: terminal screencasts as code (the default for Gimlé)

Most Gimlé tutorials are CLI workflows (`gimle deploy`, `gimle logs --follow`, cluster
bring-up), and VHS (<https://github.com/charmbracelet/vhs>) records those from a **declarative
`.tape` script** — no human typing, pixel-identical on every re-render, so a screencast can be
regenerated whenever the CLI surface changes instead of rotting. That determinism is why VHS is
the default video tool here.

**Install**: a Go binary plus two runtime deps it drives: `ttyd` and `ffmpeg`. Release tarballs:
`https://github.com/charmbracelet/vhs/releases` (`vhs_*_Linux_x86_64.tar.gz`), or
`go install github.com/charmbracelet/vhs@latest`. If `ttyd`/`ffmpeg` can't be installed in the
current environment, write and commit the `.tape` anyway and tell the user the one command to
render it locally (`vhs demo.tape`) — the tape is the reviewable artifact.

**Tape essentials** (full template: `assets/templates/demo.tape`):

```tape
Output deploy-first-module.webm
Set FontFamily "JetBrains Mono"
Set FontSize 18
Set Width 1200
Set Height 640
Set Padding 24
Set TypingSpeed 75ms

Type "gimle deploy greeter-provider-1.0.0.jar"
Enter
Sleep 4s                      # let real output land; prefer Wait /regex/ when output is known
Type "gimle instances"
Enter
Sleep 3s
Screenshot deploy-first-module-poster.png
```

Other verbs worth knowing: `Hide`/`Show` (run setup off-camera — e.g. exports, `cd`),
`Wait /ACTIVE/` (wait for output instead of guessing sleeps), `Ctrl+C`, multiple `Output` lines
(`.webm` + `.gif` from one run).

**Record against something real.** Bring up an actual local cluster first (the flow in
`gimle-console/LOCAL_DEV.md`, or the `gimle-maven-plugin` goals) — invented output in a fake
prompt is a documentation defect. Use `Hide`/`Show` around the boring bring-up, and keep
tenant/user/path details generic.

## Route 2 — Motion Canvas / Revideo: animated explainers

For concept explainers that need motion graphics beyond a terminal — animated code walkthroughs,
timeline-synced narration, shape choreography:

- **Motion Canvas** (<https://motioncanvas.io>): TypeScript, generator-based animation API
  (`yield*` tweens), with a live editor for scrubbing and audio sync. Bootstrap:
  `bun create @motion-canvas@latest` (its own project dir under `gimle-docs/diagrams/<name>/`).
  Rendering happens from the editor (browser). Pick it when a human will iterate on timing.
- **Revideo** (<https://re.video>): a Motion Canvas fork built for **headless** rendering —
  `renderVideo()` from a Node script, ffmpeg included — so Claude can render end-to-end without
  a browser session. Pick it when the video should be reproducible from CI or from an agent.
  (Note: the project has folded into the Midrender product; the framework is maintained but
  check its repo status before starting a large investment on it.)

Style: same tokens as everything else — Work Sans for titles, JetBrains Mono for code, accent
`#2a5a8c`/`#6ea8dc`, calm easing, ≤ 90 s. Scene sources live in their project dir; only the
rendered WebM/MP4 + poster go to `gimle-docs/static/video/`.

**Manim CE** (MIT, Python) exists for math/algorithm-style animation; it drags in Python +
LaTeX + ffmpeg toolchains, so reach for it only if Motion Canvas genuinely can't express the
visual — and say why in your summary.

## Route 3 — doodle videos

Hand-drawn whiteboard-style videos are the Excalidraw path — see
`references/excalidraw-doodles.md` (excalidraw-animate for draw-on reveals, excalimate for
keyframed camera work).

## ffmpeg recipes

```bash
ffmpeg -i in.webm -frames:v 1 poster.png                     # poster frame
ffmpeg -i in.gif -c:v libvpx-vp9 -b:v 0 -crf 40 out.webm     # oversized GIF → WebM
ffmpeg -i in.mp4 -c:v libvpx-vp9 -b:v 0 -crf 36 -an out.webm # MP4 → compact silent WebM
ffmpeg -i in.webm -c:v libx264 -pix_fmt yuv420p out.mp4      # Safari-friendly MP4 companion
```

Budgets: WebM ≤ 5 MB (silent tutorial), GIF ≤ 2 MB (tiny loops only), 1080p max. WebM (VP9)
is the primary format; add an MP4 companion only if the page's audience plausibly includes older
Safari.

## Embedding

Copy `assets/DocVideo.tsx` → `gimle-docs/src/components/DocVideo/index.tsx` (once), then:

```mdx
import DocVideo from '@site/src/components/DocVideo';

<DocVideo
  src="/video/deploy-first-module.webm"
  poster="/video/deploy-first-module-poster.png"
  caption="Deploying greeter-provider with gimle-cli (45 s)"
/>
```

- Tutorial videos: user-initiated playback (default props — controls, no autoplay).
- Short doodle loops: `loop autoPlay` (component mutes, inlines, and honors
  `prefers-reduced-motion` by showing the poster instead).
- Narrated videos: add a transcript in a collapsed `<details>` right below the embed.
