// Self-hosted, bundled into the jar alongside everything else -- the console is served from
// gimle-controlplane's own classpath specifically so there's no external artifact to fetch; a
// runtime Google Fonts CDN dependency would defeat that, and block first paint entirely on an
// air-gapped/egress-filtered cluster. Weights match what styles.css's --font-sans/--font-mono
// actually reference (Work Sans 300-700, JetBrains Mono 400-700).
import "@fontsource/work-sans/300.css";
import "@fontsource/work-sans/400.css";
import "@fontsource/work-sans/500.css";
import "@fontsource/work-sans/600.css";
import "@fontsource/work-sans/700.css";
import "@fontsource/jetbrains-mono/400.css";
import "@fontsource/jetbrains-mono/500.css";
import "@fontsource/jetbrains-mono/600.css";
import "@fontsource/jetbrains-mono/700.css";
import "./styles.css";

import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { RouterProvider } from "@tanstack/react-router";
import { getRouter } from "./router";

const router = getRouter();

const rootElement = document.getElementById("root")!;
createRoot(rootElement).render(
  <StrictMode>
    <RouterProvider router={router} />
  </StrictMode>,
);
