import path from "node:path";
import { fileURLToPath } from "node:url";
import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
import { gimleDataPlugin } from "./server/devApiPlugin.mjs";

const __dirname = path.dirname(fileURLToPath(import.meta.url));

// Defaults to the Gimle checkout this tool lives inside (two levels up: tools/gimle-tracker -> repo root).
// Point GIMLE_DATA_DIR at any other checkout to read its requirements files instead.
const dataDir = path.resolve(process.env.GIMLE_DATA_DIR || path.join(__dirname, "..", ".."));

export default defineConfig({
  plugins: [react(), gimleDataPlugin(dataDir)],
  server: { port: 5183 },
});
