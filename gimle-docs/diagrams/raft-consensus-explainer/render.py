#!/usr/bin/env python3
"""Renders a narrated explainer video from a JSON scene script: one D2 board + one Piper-narrated
line per scene, screenshotted at a fixed resolution and stitched into a WebM with ffmpeg.

Usage: python3 render.py scenes.json out.webm

Requires on PATH (or at the fixed paths below, matching this session's setup):
  - d2 (https://d2lang.com, MPL-2.0)            -- diagram rendering
  - Chromium headless (bundled at /opt/pw-browsers) -- SVG -> PNG screenshot
  - Piper TTS (https://github.com/rhasspy/piper, MIT) -- narration synthesis
  - ffmpeg                                       -- frame+audio -> video, and concatenation

Each scene JSON object: {"caption": str, "narration": str, "d2_source": path, "d2_target":
"steps.N" or null for a single-board source}. See README.md in this directory for the full
recipe (voice model source, install commands) to regenerate on a fresh machine.
"""
import json
import subprocess
import sys
import tempfile
import os
import shutil

WIDTH, HEIGHT = 1280, 720
CHROME = "/opt/pw-browsers/chromium-1194/chrome-linux/chrome"
PIPER = os.path.expanduser("~/tools/piper/piper") if os.path.exists(
    os.path.expanduser("~/tools/piper/piper")) else "/root/tools/piper/piper"
VOICE_MODEL = "/root/tools/voices/en-us-lessac-medium.onnx"
FRAME_HTML = """<!doctype html>
<html><head><meta charset="utf-8"><style>
  html,body {{ margin:0; padding:0; width:{width}px; height:{height}px; background:#f6f7f9;
    font-family: 'Work Sans', 'Helvetica Neue', Arial, sans-serif; overflow:hidden; }}
  * {{ box-sizing:border-box; }}
  .bar {{ height:64px; display:flex; align-items:center; padding:0 32px; background:#1b2a3d;
    color:#e8edf5; font-size:22px; font-weight:600; box-sizing:border-box; }}
  .bar .tag {{ color:#6ea8dc; font-family: 'JetBrains Mono', monospace; font-size:15px;
    margin-right:14px; font-weight:500; }}
  .stage {{ height:{stage_height}px; display:flex; align-items:center; justify-content:center; }}
  .stage img {{ max-width:{max_img_w}px; max-height:{max_img_h}px; }}
</style></head>
<body>
  <div class="bar"><span class="tag">gimle-{module}</span>{caption}</div>
  <div class="stage"><img src="file://{svg_path}"></div>
</body></html>
"""

TITLE_HTML = """<!doctype html>
<html><head><meta charset="utf-8"><style>
  html,body {{ margin:0; padding:0; width:{width}px; height:{height}px; background:#1b2a3d;
    font-family: 'Work Sans', 'Helvetica Neue', Arial, sans-serif; overflow:hidden; }}
  .wrap {{ height:{height}px; display:flex; flex-direction:column; align-items:center;
    justify-content:center; text-align:center; padding:0 80px; box-sizing:border-box; }}
  .tag {{ color:#6ea8dc; font-family: 'JetBrains Mono', monospace; font-size:20px;
    margin-bottom:22px; letter-spacing: 0.04em; }}
  h1 {{ color:#f6f7f9; font-size:44px; margin:0 0 18px 0; font-weight:700; line-height:1.25; }}
  p {{ color:#b7c2d0; font-size:22px; margin:0; max-width:900px; line-height:1.5; }}
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
  html_path = os.path.join(workdir, "title.html")
  with open(html_path, "w") as f:
    f.write(TITLE_HTML.format(width=WIDTH, height=HEIGHT, tag=tag, title=title, subtitle=subtitle))
  run([CHROME, "--headless", "--disable-gpu", "--no-sandbox",
       f"--screenshot={out_png}", f"--window-size={WIDTH},{HEIGHT}",
       "file://" + os.path.abspath(html_path)])


def render_frame(d2_source, d2_target, caption, module, out_png, workdir):
  svg_path = os.path.join(workdir, "board.svg")
  cmd = ["d2", "--sketch", "--theme=0", "--pad=30"]
  if d2_target:
    cmd.append(f"--target={d2_target}")
  cmd += [d2_source, svg_path]
  run(cmd)
  html_path = os.path.join(workdir, "frame.html")
  with open(html_path, "w") as f:
    f.write(FRAME_HTML.format(
        width=WIDTH, height=HEIGHT, stage_height=HEIGHT - 64,
        max_img_w=WIDTH - 80, max_img_h=HEIGHT - 64 - 60,
        module=module, caption=caption, svg_path=os.path.abspath(svg_path)))
  run([CHROME, "--headless", "--disable-gpu", "--no-sandbox",
       f"--screenshot={out_png}", f"--window-size={WIDTH},{HEIGHT}",
       "file://" + os.path.abspath(html_path)])


def synth_narration(text, out_wav):
  run([PIPER, "--model", VOICE_MODEL, "--output_file", out_wav],
      input=text)


def wav_duration(path):
  r = run(["ffprobe", "-v", "error", "-show_entries", "format=duration",
           "-of", "default=noprint_wrappers=1:nokey=1", path])
  return float(r.stdout.strip())


def build_scene_clip(png, wav, out_webm):
  # Pad with 0.4s of silence-hold at the end so a cut doesn't feel abrupt.
  duration = wav_duration(wav) + 0.6
  run(["ffmpeg", "-y", "-loop", "1", "-i", png, "-i", wav,
       "-c:v", "libvpx-vp9", "-pix_fmt", "yuv420p", "-r", "24",
       "-c:a", "libopus", "-b:a", "96k",
       "-t", str(duration), out_webm])


def main():
  scenes_path, out_path = sys.argv[1], sys.argv[2]
  with open(scenes_path) as f:
    scenes = json.load(f)

  workdir = tempfile.mkdtemp(prefix="narrated-video-")
  clip_paths = []
  transcript_lines = []
  try:
    for i, scene in enumerate(scenes):
      scene_dir = os.path.join(workdir, f"scene-{i:02d}")
      os.makedirs(scene_dir, exist_ok=True)
      png = os.path.join(scene_dir, "frame.png")
      wav = os.path.join(scene_dir, "narration.wav")
      clip = os.path.join(scene_dir, "clip.webm")

      if scene.get("type") == "title":
        render_title_frame(scene.get("tag", ""), scene["title"], scene.get("subtitle", ""),
                            png, scene_dir)
      else:
        render_frame(scene["d2_source"], scene.get("d2_target"), scene["caption"],
                     scene.get("module", ""), png, scene_dir)
      synth_narration(scene["narration"], wav)
      build_scene_clip(png, wav, clip)
      clip_paths.append(clip)
      transcript_lines.append(scene["narration"])
      label = scene.get("caption") or scene.get("title", "")
      print(f"scene {i}: {label!r} ({wav_duration(wav):.1f}s narration)")

    concat_list = os.path.join(workdir, "concat.txt")
    with open(concat_list, "w") as f:
      for c in clip_paths:
        f.write(f"file '{os.path.abspath(c)}'\n")
    run(["ffmpeg", "-y", "-f", "concat", "-safe", "0", "-i", concat_list,
         "-c", "copy", out_path])

    poster_path = out_path.rsplit(".", 1)[0] + "-poster.png"
    run(["ffmpeg", "-y", "-i", out_path, "-frames:v", "1", poster_path])

    transcript_path = out_path.rsplit(".", 1)[0] + "-transcript.txt"
    with open(transcript_path, "w") as f:
      f.write("\n\n".join(transcript_lines))

    print(f"\nwrote {out_path}, {poster_path}, {transcript_path}")
  finally:
    shutil.rmtree(workdir, ignore_errors=True)


if __name__ == "__main__":
  main()
