#!/usr/bin/env node
// Production entrypoint: serves the built `dist/` app plus a read-only /api/data
// endpoint, reading the three RTM files from --data-dir (or GIMLE_DATA_DIR) on
// every request. Run `npm run build` first.
import http from "node:http";
import { readFile } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { loadGimleData } from "./readData.mjs";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const distDir = path.join(__dirname, "..", "dist");

function parseArgs(argv) {
  const args = { port: 4173 };
  for (let i = 0; i < argv.length; i++) {
    if (argv[i] === "--data-dir") args.dataDir = argv[++i];
    else if (argv[i] === "--port") args.port = Number(argv[++i]);
    else if (argv[i] === "--help" || argv[i] === "-h") args.help = true;
  }
  return args;
}

const args = parseArgs(process.argv.slice(2));

if (args.help) {
  console.log(
    "Usage: node server/serve.mjs --data-dir <path-to-gimle-checkout> [--port 4173]\n" +
      "  (or set GIMLE_DATA_DIR instead of --data-dir)\n" +
      "The directory must contain requirements-matrix.json, rtm.json and uat-checklist.json.",
  );
  process.exit(0);
}

const dataDir = path.resolve(args.dataDir || process.env.GIMLE_DATA_DIR || process.cwd());

const MIME = {
  ".html": "text/html",
  ".js": "text/javascript",
  ".css": "text/css",
  ".svg": "image/svg+xml",
  ".json": "application/json",
  ".woff2": "font/woff2",
};

async function serveStatic(res, pathname) {
  const requested = path.join(distDir, pathname === "/" ? "index.html" : pathname);
  const resolved = path.normalize(requested);
  if (!resolved.startsWith(distDir)) {
    res.statusCode = 403;
    res.end("Forbidden");
    return;
  }
  try {
    const content = await readFile(resolved);
    res.setHeader("Content-Type", MIME[path.extname(resolved)] || "application/octet-stream");
    res.end(content);
  } catch {
    // client-side route (e.g. /requirements/GIMLE-001) - fall back to the SPA shell
    const indexHtml = await readFile(path.join(distDir, "index.html"));
    res.setHeader("Content-Type", "text/html");
    res.end(indexHtml);
  }
}

const server = http.createServer(async (req, res) => {
  const url = new URL(req.url, `http://${req.headers.host}`);

  if (url.pathname === "/api/data") {
    res.setHeader("Content-Type", "application/json");
    try {
      res.end(JSON.stringify(await loadGimleData(dataDir)));
    } catch (err) {
      res.statusCode = 500;
      res.end(JSON.stringify({ error: err.message }));
    }
    return;
  }

  await serveStatic(res, url.pathname);
});

server.listen(args.port, () => {
  console.log(`Gimlé Tracker running at http://localhost:${args.port}`);
  console.log(`Reading requirements data from: ${dataDir}`);
});
