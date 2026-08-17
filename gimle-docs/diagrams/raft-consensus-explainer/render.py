#!/usr/bin/env python3
"""Renders a narrated explainer video from a JSON scene script: one D2 board + one Piper-narrated
line per scene, screenshotted at 2x resolution and stitched into a WebM with ffmpeg -- a subtle
Ken Burns zoom, cross-fades between cuts, loudness-normalized narration, and a WebVTT caption
track come out of the same pass.

Usage: python3 render.py scenes.json out.webm

Requires on PATH (or at the fixed paths below, matching this session's setup):
  - d2 (https://d2lang.com, MPL-2.0)            -- diagram rendering
  - Chromium headless (bundled at /opt/pw-browsers) -- SVG -> PNG screenshot
  - Piper TTS (https://github.com/rhasspy/piper, MIT) -- narration synthesis
  - ffmpeg                                       -- frame+audio -> video, mastering, concatenation

Each scene JSON object: {"caption": str, "narration": str, "d2_source": path, "d2_target":
"steps.N" or null for a single-board source} or {"type": "title", "tag", "title", "subtitle",
"narration"}. See README.md in this directory for the full recipe (voice model source, install
commands) to regenerate on a fresh machine.
"""
import json
import subprocess
import sys
import tempfile
import os
import shutil

WIDTH, HEIGHT = 1280, 720
RENDER_SCALE = 2  # frames are screenshotted at 2x and downscaled during zoompan, so the Ken
                   # Burns zoom never magnifies an already-1x raster past its native detail.
FPS = 24
CUT_FADE = 0.35  # video+audio fade in/out per clip, seconds -- softens each scene boundary
                 # into a cross-fade-like cut without the multi-input xfade filter graph's
                 # complexity of tracking every clip's absolute concat-timeline offset.
END_HOLD = 0.6   # silence-hold appended after narration ends, before the next cut

CHROME = "/opt/pw-browsers/chromium-1194/chrome-linux/chrome"
PIPER = os.path.expanduser("~/tools/piper/piper") if os.path.exists(
    os.path.expanduser("~/tools/piper/piper")) else "/root/tools/piper/piper"
VOICE_MODEL = os.path.expanduser("~/tools/voices/en-us-ryan-high.onnx") if os.path.exists(
    os.path.expanduser("~/tools/voices/en-us-ryan-high.onnx")) else "/root/tools/voices/en-us-ryan-high.onnx"
# Slightly slower than Piper's own default cadence (1.0) -- more deliberate pacing suits narrated
# technical explanation better than conversational speed. noise_scale/noise_w left at Piper's own
# defaults: lower values flatten prosody into something noticeably more robotic.
PIPER_ARGS = ["--length_scale", "1.05", "--sentence_silence", "0.15"]

# Split into two layers so the Ken Burns zoom (build_scene_clip) can push in on the diagram alone
# without progressively cropping the edge-anchored title bar / progress counter out of frame: a
# centered zoom crops in from every edge equally, and those two elements sit flush against the
# left/right/bottom edges by design. STAGE_HTML renders just the diagram, at full frame size, with
# a transparent strip reserved (not drawn) where the bar belongs, so the diagram's own vertical
# centering matches the original single-layer layout exactly. CHROME_HTML renders only the bar and
# progress counter, opaque where they are and transparent everywhere else, composited back on top
# *after* zoompan -- so the chrome itself never moves or crops, only the diagram under it does.
STAGE_HTML = """<!doctype html>
<html><head><meta charset="utf-8"><style>
  html,body {{ margin:0; padding:0; width:{width}px; height:{height}px; background:#f6f7f9;
    font-family: 'Work Sans', 'Helvetica Neue', Arial, sans-serif; overflow:hidden; }}
  * {{ box-sizing:border-box; }}
  .spacer {{ height:{bar_h}px; }}
  .stage {{ height:{stage_height}px; display:flex; align-items:center; justify-content:center; }}
  .stage img {{ max-width:{max_img_w}px; max-height:{max_img_h}px; }}
</style></head>
<body>
  <div class="spacer"></div>
  <div class="stage"><img src="file://{svg_path}"></div>
</body></html>
"""

CHROME_HTML = """<!doctype html>
<html><head><meta charset="utf-8"><style>
  html,body {{ margin:0; padding:0; width:{width}px; height:{height}px; background:transparent;
    font-family: 'Work Sans', 'Helvetica Neue', Arial, sans-serif; overflow:hidden; }}
  * {{ box-sizing:border-box; }}
  .bar {{ height:{bar_h}px; display:flex; align-items:center; padding:0 {pad}px; background:#1b2a3d;
    color:#e8edf5; font-size:{caption_fs}px; font-weight:600; box-sizing:border-box; }}
  .bar .tag {{ color:#6ea8dc; font-family: 'JetBrains Mono', monospace; font-size:{tag_fs}px;
    margin-right:{pad}px; font-weight:500; }}
  .progress {{ position:absolute; right:{pad}px; bottom:{prog_bottom}px; color:#9aa7b8;
    font-family: 'JetBrains Mono', monospace; font-size:{tag_fs}px;
    background: rgba(246,247,249,0.85); padding:2px 8px; border-radius:4px; }}
</style></head>
<body>
  <div class="bar"><span class="tag">gimle-{module}</span>{caption}</div>
  <div class="progress">{scene_num} / {scene_total}</div>
</body></html>
"""

TITLE_HTML = """<!doctype html>
<html><head><meta charset="utf-8"><style>
  html,body {{ margin:0; padding:0; width:{width}px; height:{height}px; background:#1b2a3d;
    font-family: 'Work Sans', 'Helvetica Neue', Arial, sans-serif; overflow:hidden; }}
  .wrap {{ height:{height}px; display:flex; flex-direction:column; align-items:center;
    justify-content:center; text-align:center; padding:0 {pad}px; box-sizing:border-box; }}
  .tag {{ color:#6ea8dc; font-family: 'JetBrains Mono', monospace; font-size:{tag_fs}px;
    margin-bottom:{gap}px; letter-spacing: 0.04em; }}
  h1 {{ color:#f6f7f9; font-size:{title_fs}px; margin:0 0 {gap}px 0; font-weight:700; line-height:1.25; }}
  p {{ color:#b7c2d0; font-size:{sub_fs}px; margin:0; max-width:{sub_w}px; line-height:1.5; }}
</style></head>
<body>
  <div class="wrap">
    <div class="tag">{tag}</div>
    <h1>{title}</h1>
    <p>{subtitle}</p>
  </div>
</body></html>
"""


def run(cmd, **kw):
  r = subprocess.run(cmd, capture_output=True, text=True, **kw)
  if r.returncode != 0:
    raise RuntimeError(f"command failed: {' '.join(cmd)}\n{r.stdout}\n{r.stderr}")
  return r


def render_title_frame(tag, title, subtitle, out_png, workdir):
  w, h = WIDTH * RENDER_SCALE, HEIGHT * RENDER_SCALE
  html_path = os.path.join(workdir, "title.html")
  with open(html_path, "w") as f:
    f.write(TITLE_HTML.format(
        width=w, height=h, pad=80 * RENDER_SCALE, gap=18 * RENDER_SCALE,
        tag_fs=20 * RENDER_SCALE, title_fs=44 * RENDER_SCALE, sub_fs=22 * RENDER_SCALE,
        sub_w=900 * RENDER_SCALE, tag=tag, title=title, subtitle=subtitle))
  run([CHROME, "--headless", "--disable-gpu", "--no-sandbox",
       f"--screenshot={out_png}", f"--window-size={w},{h}",
       "file://" + os.path.abspath(html_path)])


def render_frame(d2_source, d2_target, caption, module, scene_num, scene_total,
                  out_stage_png, out_chrome_png, workdir):
  """Renders a content scene as two layers -- see STAGE_HTML/CHROME_HTML's own comment for why."""
  svg_path = os.path.join(workdir, "board.svg")
  cmd = ["d2", "--sketch", "--theme=0", "--pad=30"]
  if d2_target:
    cmd.append(f"--target={d2_target}")
  cmd += [d2_source, svg_path]
  run(cmd)

  w, h = WIDTH * RENDER_SCALE, HEIGHT * RENDER_SCALE
  bar_h = 64 * RENDER_SCALE
  stage_html = os.path.join(workdir, "stage.html")
  with open(stage_html, "w") as f:
    f.write(STAGE_HTML.format(
        width=w, height=h, bar_h=bar_h,
        stage_height=h - bar_h, max_img_w=w - 80 * RENDER_SCALE, max_img_h=h - bar_h - 60 * RENDER_SCALE,
        svg_path=os.path.abspath(svg_path)))
  run([CHROME, "--headless", "--disable-gpu", "--no-sandbox",
       f"--screenshot={out_stage_png}", f"--window-size={w},{h}",
       "file://" + os.path.abspath(stage_html)])

  # Chrome (bar + progress counter) is rendered at 1x -- it's never zoomed, so it needs no extra
  # resolution headroom -- with a transparent background via --default-background-color=00000000.
  chrome_html = os.path.join(workdir, "chrome.html")
  with open(chrome_html, "w") as f:
    f.write(CHROME_HTML.format(
        width=WIDTH, height=HEIGHT, bar_h=64, pad=32, caption_fs=22, tag_fs=15, prog_bottom=18,
        module=module, caption=caption, scene_num=scene_num, scene_total=scene_total))
  run([CHROME, "--headless", "--disable-gpu", "--no-sandbox",
       "--default-background-color=00000000",
       f"--screenshot={out_chrome_png}", f"--window-size={WIDTH},{HEIGHT}",
       "file://" + os.path.abspath(chrome_html)])


def synth_narration(text, out_wav):
  run([PIPER, "--model", VOICE_MODEL, *PIPER_ARGS, "--output_file", out_wav], input=text)


def wav_duration(path):
  r = run(["ffprobe", "-v", "error", "-show_entries", "format=duration",
           "-of", "default=noprint_wrappers=1:nokey=1", path])
  return float(r.stdout.strip())


def build_scene_clip(png, wav, out_webm, chrome_png=None):
  """png is the zoompan target (either a full single-layer frame, or a bar-less stage render when
  chrome_png is given). chrome_png, if given, is composited back on top *after* zoompan so the
  bar/progress counter it carries never moves or gets cropped by the zoom -- see STAGE_HTML/
  CHROME_HTML's own comment."""
  duration = wav_duration(wav) + END_HOLD
  frames = max(1, round(duration * FPS))
  fade_out_start = max(0.0, duration - CUT_FADE)
  # zoompan drives a slow, centered Ken Burns push-in (1.00 -> ~1.06 over the clip) off the 2x
  # source frame, so the zoom never has to magnify an already-1x raster past its native detail.
  zoomed = (
      f"[0:v]scale={WIDTH * RENDER_SCALE}:{HEIGHT * RENDER_SCALE},"
      f"zoompan=z='min(zoom+0.0015,1.06)':d={frames}:s={WIDTH}x{HEIGHT}:fps={FPS}:"
      f"x='iw/2-(iw/zoom/2)':y='ih/2-(ih/zoom/2)'[zoomed]"
  )
  af = (
      f"loudnorm=I=-16:TP=-1.5:LRA=11,"
      f"afade=t=in:st=0:d={min(0.2, CUT_FADE)},afade=t=out:st={fade_out_start}:d={CUT_FADE}"
  )
  inputs = ["-loop", "1", "-i", png]
  if chrome_png:
    inputs += ["-loop", "1", "-i", chrome_png]
  inputs += ["-i", wav]
  audio_idx = 2 if chrome_png else 1

  if chrome_png:
    filter_complex = (
        f"{zoomed};"
        f"[zoomed][1:v]overlay=0:0:shortest=1[composited];"
        f"[composited]fade=t=in:st=0:d={CUT_FADE},fade=t=out:st={fade_out_start}:d={CUT_FADE}[v];"
        f"[{audio_idx}:a]{af}[a]"
    )
  else:
    filter_complex = (
        f"{zoomed};"
        f"[zoomed]fade=t=in:st=0:d={CUT_FADE},fade=t=out:st={fade_out_start}:d={CUT_FADE}[v];"
        f"[{audio_idx}:a]{af}[a]"
    )
  run(["ffmpeg", "-y", *inputs,
       "-filter_complex", filter_complex, "-map", "[v]", "-map", "[a]",
       "-c:v", "libvpx-vp9", "-pix_fmt", "yuv420p", "-r", str(FPS),
       "-c:a", "libopus", "-b:a", "96k",
       "-t", str(duration), out_webm])


def format_vtt_timestamp(seconds):
  hours = int(seconds // 3600)
  minutes = int((seconds % 3600) // 60)
  secs = seconds % 60
  return f"{hours:02d}:{minutes:02d}:{secs:06.3f}"


def split_into_caption_cues(narration, start, end):
  """Splits one scene's narration into sentence-level cues, each given a slice of [start, end]
  proportional to its own character count -- Piper gives no per-word/per-sentence timestamps, so
  this is a lightweight heuristic, not forced alignment, but it tracks speech pace far better than
  one giant block of text held on screen for the whole scene."""
  import re
  sentences = [s.strip() for s in re.split(r'(?<=[.!?])\s+', narration) if s.strip()]
  if len(sentences) <= 1:
    return [(start, end, narration)]
  total_chars = sum(len(s) for s in sentences)
  cues = []
  cursor = start
  span = end - start
  for i, sentence in enumerate(sentences):
    share = len(sentence) / total_chars
    cue_end = end if i == len(sentences) - 1 else cursor + span * share
    cues.append((cursor, cue_end, sentence))
    cursor = cue_end
  return cues


def main():
  scenes_path, out_path = sys.argv[1], sys.argv[2]
  with open(scenes_path) as f:
    scenes = json.load(f)

  workdir = tempfile.mkdtemp(prefix="narrated-video-")
  clip_paths = []
  transcript_lines = []
  vtt_cues = []
  timeline = 0.0
  try:
    for i, scene in enumerate(scenes):
      scene_dir = os.path.join(workdir, f"scene-{i:02d}")
      os.makedirs(scene_dir, exist_ok=True)
      png = os.path.join(scene_dir, "frame.png")
      chrome_png = None
      wav = os.path.join(scene_dir, "narration.wav")
      clip = os.path.join(scene_dir, "clip.webm")

      if scene.get("type") == "title":
        render_title_frame(scene.get("tag", ""), scene["title"], scene.get("subtitle", ""),
                            png, scene_dir)
      else:
        chrome_png = os.path.join(scene_dir, "chrome.png")
        render_frame(scene["d2_source"], scene.get("d2_target"), scene["caption"],
                     scene.get("module", ""), i + 1, len(scenes), png, chrome_png, scene_dir)
      synth_narration(scene["narration"], wav)
      build_scene_clip(png, wav, clip, chrome_png)
      clip_paths.append(clip)
      transcript_lines.append(scene["narration"])

      narr_duration = wav_duration(wav)
      vtt_cues.extend(split_into_caption_cues(scene["narration"], timeline, timeline + narr_duration))
      timeline += narr_duration + END_HOLD

      label = scene.get("caption") or scene.get("title", "")
      print(f"scene {i}: {label!r} ({narr_duration:.1f}s narration)")

    concat_list = os.path.join(workdir, "concat.txt")
    with open(concat_list, "w") as f:
      for c in clip_paths:
        f.write(f"file '{os.path.abspath(c)}'\n")
    run(["ffmpeg", "-y", "-f", "concat", "-safe", "0", "-i", concat_list,
         "-c", "copy", out_path])

    # Seek past the first clip's own fade-in (CUT_FADE) -- frame 0 is mid-fade, close to solid
    # black, and makes a useless poster/reduced-motion-fallback image.
    poster_path = out_path.rsplit(".", 1)[0] + "-poster.png"
    run(["ffmpeg", "-y", "-ss", str(CUT_FADE + 0.3), "-i", out_path,
         "-frames:v", "1", "-update", "1", poster_path])

    transcript_path = out_path.rsplit(".", 1)[0] + "-transcript.txt"
    with open(transcript_path, "w") as f:
      f.write("\n\n".join(transcript_lines))

    vtt_path = out_path.rsplit(".", 1)[0] + ".vtt"
    with open(vtt_path, "w") as f:
      f.write("WEBVTT\n\n")
      for start, end, text in vtt_cues:
        f.write(f"{format_vtt_timestamp(start)} --> {format_vtt_timestamp(end)}\n{text}\n\n")

    print(f"\nwrote {out_path}, {poster_path}, {transcript_path}, {vtt_path}")
  finally:
    shutil.rmtree(workdir, ignore_errors=True)


if __name__ == "__main__":
  main()
