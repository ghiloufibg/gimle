import {
  Outlet,
  Link,
  createRootRoute,
  useRouter,
  useLocation,
  HeadContent,
} from "@tanstack/react-router";
import { useEffect, useRef } from "react";

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
          Ivaldi has four screens: the blueprint list, the designer, the cluster list and the
          runner. Nothing lives at this address.
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
      // A real file at the site root as well as the inline emblem: the browser asks for
      // /favicon.ico regardless, and a 404 on every page load is noise in the console.
      { rel: "icon", type: "image/svg+xml", href: `${import.meta.env.BASE_URL}favicon.svg` },
      { rel: "alternate icon", href: `${import.meta.env.BASE_URL}favicon.ico` },
      { rel: "apple-touch-icon", href: IVALDI_FAVICON },
    ],
  }),
  component: RootComponent,
  notFoundComponent: NotFoundComponent,
  errorComponent: ErrorComponent,
});

function RootComponent() {
  // A route change left focus wherever it happened to be -- <body>, almost always, since the
  // outgoing screen's own focused element is simply gone -- so a keyboard/screen-reader user got
  // no indication navigation happened at all, and had to Tab from the very top of the new screen
  // every time. Moving focus to this always-present, otherwise invisible landmark on every
  // pathname change (never on first mount, so a fresh page load still starts wherever the browser
  // itself puts focus) gives them a real, announced landing point without singling out one
  // route's own heading, which would need matching markup on all four screens.
  const { pathname } = useLocation();
  const mounted = useRef(false);
  const landmark = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!mounted.current) {
      mounted.current = true;
      return;
    }
    landmark.current?.focus();
  }, [pathname]);

  return (
    <>
      <HeadContent />
      <div ref={landmark} tabIndex={-1} className="sr-only">
        Navigated to {pathname}
      </div>
      <Outlet />
      <Toaster position="bottom-right" />
    </>
  );
}
