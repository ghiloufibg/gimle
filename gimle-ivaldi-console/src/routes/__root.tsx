import { Outlet, Link, createRootRoute, useRouter, HeadContent } from "@tanstack/react-router";

import { Toaster } from "@/components/ui/sonner";
import { IVALDI_FAVICON } from "@/components/ivaldi/IvaldiEmblem";

import appCss from "../styles.css?url";

function NotFoundComponent() {
  return (
    <div className="flex min-h-screen items-center justify-center bg-background px-4">
      <div className="max-w-md text-center">
        <div className="hud-label">404</div>
        <h1 className="mt-2 text-xl font-semibold text-foreground">Route not found</h1>
        <p className="mt-2 text-sm text-muted-foreground">
          Ivaldi has a blueprint list and a designer, nothing else.
        </p>
        <div className="mt-6">
          <Link
            to="/"
            className="inline-flex items-center justify-center rounded-sm bg-primary px-3 py-1.5 font-mono text-[11px] text-primary-foreground"
          >
            Blueprints
          </Link>
        </div>
      </div>
    </div>
  );
}

function ErrorComponent({ error, reset }: { error: Error; reset: () => void }) {
  console.error(error);
  const router = useRouter();

  return (
    <div className="flex min-h-screen items-center justify-center bg-background px-4">
      <div className="max-w-md text-center">
        <div className="hud-label">Error</div>
        <h1 className="mt-2 text-xl font-semibold text-foreground">This screen didn't load</h1>
        <p className="mt-2 text-sm text-muted-foreground">{error.message}</p>
        <div className="mt-6 flex flex-wrap justify-center gap-2">
          <button
            onClick={() => {
              router.invalidate();
              reset();
            }}
            className="inline-flex items-center justify-center rounded-sm bg-primary px-3 py-1.5 font-mono text-[11px] text-primary-foreground"
          >
            Retry
          </button>
        </div>
      </div>
    </div>
  );
}

export const Route = createRootRoute({
  head: () => ({
    meta: [
      { charSet: "utf-8" },
      { name: "viewport", content: "width=device-width, initial-scale=1" },
      { title: "Ivaldi — Gimlé cluster designer" },
      {
        name: "description",
        content:
          "Design a local Gimlé cluster on a canvas, validate it live, export the exact YAML files Gimlé's tools consume.",
      },
      { property: "og:type", content: "website" },
      { name: "twitter:card", content: "summary_large_image" },
    ],
    links: [
      { rel: "stylesheet", href: appCss },
      {
        rel: "stylesheet",
        href: "https://fonts.googleapis.com/css2?family=Work+Sans:wght@400;500;600;700&family=JetBrains+Mono:wght@400;500;700&display=swap",
      },
      { rel: "icon", href: IVALDI_FAVICON, type: "image/svg+xml" },
    ],
  }),
  component: RootComponent,
  notFoundComponent: NotFoundComponent,
  errorComponent: ErrorComponent,
});

function RootComponent() {
  return (
    <>
      <HeadContent />
      <Outlet />
      <Toaster position="bottom-right" />
    </>
  );
}
