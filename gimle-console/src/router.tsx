import { QueryClient } from "@tanstack/react-query";
import { createRouter } from "@tanstack/react-router";
import { routeTree } from "./routeTree.gen";

export const getRouter = () => {
  const queryClient = new QueryClient();

  const router = createRouter({
    routeTree,
    context: { queryClient },
    scrollRestoration: true,
    defaultPreloadStaleTime: 0,
    // Matches vite.config.ts's `base` condition: the production build is served by
    // gimle-controlplane's ApiServer at /console, but `vite dev` still serves from root.
    // import.meta.env.DEV is Vite's own dev-vs-build flag, so this stays in sync automatically.
    basepath: import.meta.env.DEV ? "/" : "/console",
  });

  return router;
};

declare module "@tanstack/react-router" {
  interface Register {
    router: ReturnType<typeof getRouter>;
  }
}
