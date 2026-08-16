import { QueryClient } from "@tanstack/react-query";
import { createRouter } from "@tanstack/react-router";
import { routeTree } from "./routeTree.gen";

export const getRouter = () => {
  const queryClient = new QueryClient();

  return createRouter({
    routeTree,
    context: { queryClient },
    scrollRestoration: true,
    defaultPreloadStaleTime: 0,
    // Matches vite.config.ts's `base` condition: the production build is bundled into this
    // module's jar for serving from a /console sub-path, but `vite dev` still serves from root.
    // import.meta.env.DEV is Vite's own dev-vs-build flag, so this stays in sync automatically --
    // same convention the sibling consoles' own router.tsx uses.
    basepath: import.meta.env.DEV ? "/" : "/console",
  });
};

declare module "@tanstack/react-router" {
  interface Register {
    router: ReturnType<typeof getRouter>;
  }
}
