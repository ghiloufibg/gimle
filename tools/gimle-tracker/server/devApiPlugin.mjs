import { loadGimleData } from "./readData.mjs";

/**
 * Wires GET /api/data into the Vite dev server, reading straight off dataDir on every
 * request. Kept as a Vite plugin (rather than a separate dev process) so `npm run dev`
 * is one command with hot reload.
 */
export function gimleDataPlugin(dataDir) {
  return {
    name: "gimle-data-api",
    configureServer(server) {
      server.middlewares.use("/api/data", async (_req, res) => {
        res.setHeader("Content-Type", "application/json");
        try {
          const data = await loadGimleData(dataDir);
          res.end(JSON.stringify(data));
        } catch (err) {
          res.statusCode = 500;
          res.end(JSON.stringify({ error: err.message }));
        }
      });
    },
  };
}
