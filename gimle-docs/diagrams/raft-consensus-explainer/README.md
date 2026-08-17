# Narrated concept explainer videos

`render.py` in this directory renders a short narrated video from a JSON scene script: one D2
board (or a title card) plus one voiced line per scene, screenshotted at a fixed 1280×720
resolution and stitched into a WebM with ffmpeg. It's a small, self-contained pipeline, not a
framework — reused as-is by `../swim-gossip-explainer/scenes.json` for the second video, and
intended to be reused again for future concept explainers rather than copied.

Every tool in the chain is open source and runs fully offline once installed:

| Step | Tool | License |
|---|---|---|
| Diagram rendering | [D2](https://d2lang.com) | MPL-2.0 |
| Screenshotting | Chromium headless (already bundled at `/opt/pw-browsers` in this environment) | BSD-style |
| Narration synthesis | [Piper](https://github.com/rhasspy/piper) | MIT |
| Video/audio assembly | [ffmpeg](https://ffmpeg.org) | LGPL/GPL (used as an external tool, not linked) |

## One-time setup

D2 and Chromium are already covered by the rest of `gimle-docs/diagrams/` tooling (see
`.claude/skills/bragi/scripts/install-d2.sh`). Piper and a full (audio-capable) ffmpeg need their
own install:

```bash
# Piper binary (MIT)
curl -fsSL -o piper.tar.gz \
  https://github.com/rhasspy/piper/releases/download/2023.11.14-2/piper_linux_x86_64.tar.gz
tar xzf piper.tar.gz -C ~/tools/

# A voice model -- Hugging Face is the canonical source but is blocked by some egress policies;
# the same models are also attached to a piper GitHub release, which usually isn't. en-us-ryan-high
# is the voice this pipeline actually uses (22.05kHz); en-us-lessac-medium (16kHz) also works and
# is smaller, if Ryan-high's ~100MB download isn't worth it for a lower-stakes render:
curl -fsSL -o voice.tar.gz \
  https://github.com/rhasspy/piper/releases/download/v0.0.2/voice-en-us-ryan-high.tar.gz
mkdir -p ~/tools/voices && tar xzf voice.tar.gz -C ~/tools/voices/
# (or, if reachable: https://huggingface.co/rhasspy/piper-voices/resolve/main/en/en_US/ryan/high/)

# ffmpeg: the one bundled under /opt/pw-browsers/ffmpeg-*/ is Playwright's silent-recording-only
# build (no audio encoders) -- install a full one instead.
apt-get install -y ffmpeg
```

`render.py` looks for the Piper binary/model at `~/tools/piper/piper` and
`~/tools/voices/en-us-ryan-high.onnx` — adjust the `PIPER`/`VOICE_MODEL` constants at the top of
the script if yours land elsewhere.

## Rendering

```bash
python3 render.py scenes.json out.webm
```

Produces `out.webm`, `out-poster.png` (first frame), and `out-transcript.txt` (narration lines
joined, for the collapsed `<details>` transcript every narrated embed needs — see
`.claude/skills/bragi/references/tutorial-videos.md`'s accessibility section).

## Writing a new scene script

A scene is either a diagram board:

```json
{
  "caption": "Shown in the frame's title bar.",
  "module": "mimir",
  "d2_source": "/absolute/path/to/some-diagram.d2",
  "d2_target": "steps.3",
  "narration": "What Piper speaks for this scene. Plain sentences, no markdown -- it's read literally."
}
```

or a title card (no diagram):

```json
{
  "type": "title",
  "tag": "gimle-mimir · concepts",
  "title": "Big headline",
  "subtitle": "One or two sentences of context.",
  "narration": "What Piper speaks over this card."
}
```

`d2_target` matches the `--target` flag `scripts/render-diagrams.sh` already uses for extracting a
single board from a `steps:`/`scenarios:` source — reuse the *same* `.d2` file the doc page's own
animated diagram embed uses, so the video and the page never drift into showing different pictures
for the same concept.

## Design notes

- **1280×720, house colors, no background music** — narration carries the pacing; a static frame
  held for exactly the narration's own duration (plus a 0.6s hold) avoids needing separate timing
  authored by hand.
- **Voice: Ryan (high quality, 22.05kHz)**, not the medium-quality Lessac voice this pipeline
  started with — a real fidelity upgrade (Lessac-medium is 16kHz), fetched from the same GitHub
  release. `--length_scale 1.05` paces it slightly slower than Piper's own default cadence, more
  deliberate for narrated technical explanation than conversational speed.
- **Piper over espeak-ng**: both installed cleanly in this environment; Piper's neural voice is far
  more listenable for anything longer than a few seconds. espeak-ng remains a reasonable fallback
  if a future environment can't reach Piper's release artifacts at all.
- **Two-layer compositing per content scene** (`render_frame`'s `STAGE_HTML`/`CHROME_HTML`,
  `build_scene_clip`'s `overlay` filter): the title bar and scene-progress counter are rendered
  separately from the diagram and composited back on *after* the Ken Burns zoom, not zoomed
  themselves. A single-layer zoom crops in from every edge equally, and both of those elements sit
  flush against an edge by design -- zooming them along with the diagram progressively clipped
  them. Rendering the diagram alone at 2x resolution and zooming into that gives the zoom real
  pixel headroom too, so it never magnifies an already-1x raster past its native detail.
- **loudnorm (EBU R128, -16 LUFS / -1.5 dBTP)** on every narration clip — Piper's own output level
  varies scene to scene; without this, playback volume audibly steps up and down between cuts.
- **WebVTT captions**, sentence-split with duration allocated proportional to each sentence's
  character count (`split_into_caption_cues`) — Piper gives no per-word/per-sentence timestamps, so
  this is a lightweight heuristic, not forced alignment, but it tracks speech pace far better than
  holding the whole scene's narration on screen as one block. Passed to `DocVideo`'s `captions`
  prop, which renders a real `<track kind="captions">` -- not burned into the video, so a viewer can
  toggle them and screen readers/search engines can index the text.
- **Why not Motion Canvas/Revideo for this**: those suit hand-choreographed motion graphics: this
  content is "the same diagram the doc page already has, narrated," so reusing the D2 source
  directly (one render path, zero drift between the still diagram and the video) was the better
  fit than re-authoring the same picture a second time in a different tool.
