# Gimlé Tracker

A small, read-only React app for browsing Gimlé's requirements. It reads
`requirements-matrix.json`, `rtm.json` and `uat-checklist.json` straight off
disk from a directory you point it at, merges them by requirement id, and
renders a filterable list plus a real page per requirement
(`/requirements/GIMLE-001`, shareable/bookmarkable). It never writes to those
files or anywhere else.

Standalone tool, not a Gimlé platform module — it isn't part of the Maven
reactor and has no bearing on `RTM.md`/`requirements-matrix.json` itself; it's
just a viewer for them.

## How it works

- `server/readData.mjs` reads and merges the three JSON files fresh on every
  request (no caching, no database — it's a live, read-only view of whatever
  is on disk right now).
- In dev, `server/devApiPlugin.mjs` wires that into the Vite dev server as
  `GET /api/data`.
- In production, `server/serve.mjs` is a small dependency-free Node HTTP
  server that serves the built `dist/` app plus the same `/api/data`
  endpoint.
- The React app (`src/`) fetches `/api/data` once and renders three routes:
  `/` (dashboard), `/requirements` (filterable list), `/requirements/:id`
  (detail page).

## Setup

```bash
cd tools/gimle-tracker
npm install
```

## Development

Point it at a Gimlé checkout containing the three JSON files (defaults to the
repo this tool lives in, i.e. two directories up):

```bash
GIMLE_DATA_DIR=/path/to/gimle npm run dev
```

Open the printed local URL (default `http://localhost:5183`).

## Production

```bash
npm run build
npm run start -- --data-dir /path/to/gimle
# or: GIMLE_DATA_DIR=/path/to/gimle npm run start
```

Open `http://localhost:4173` (override with `--port`). The data directory is
re-read on every page load and every requirement view — restart nothing to
pick up a fresh `mvn` run's regenerated JSON files.

## Data directory requirements

The directory passed via `--data-dir` / `GIMLE_DATA_DIR` must contain:

- `requirements-matrix.json`
- `rtm.json`
- `uat-checklist.json`

These are exactly the three files `scripts/generate_requirements_docs.py`
regenerates at the Gimlé repo root — point this tool at that repo root (or
any checkout that carries current copies of the three files).
