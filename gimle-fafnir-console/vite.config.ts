import { defineConfig } from "vite";

// Placeholder build config for the not-yet-generated Fafnir console SPA -- once Lovable's real
// output lands here, this file gets replaced by its own (React/TanStack Router/Tailwind, mirroring
// gimle-console/vite.config.ts). Until then this just proves out the Maven <-> Bun <-> dist/
// pipeline gimle-fafnir depends on (see this module's own pom.xml and FafnirMain's BundledSpa
// lookup at "fafnir-console/index.html").
//
// `base` is only set for the production build, not `vite dev`: the built dist/ is served by
// gimle-fafnir's own FafnirServer at /console (see SpaStaticHandler) -- without a matching `base`,
// every asset URL in the built index.html would be absolute from site root and 404 under that
// path. Same reasoning as gimle-console/vite.config.ts's own identical comment.
export default defineConfig(({ command }) => ({
  base: command === "build" ? "/console/" : "/",
}));
