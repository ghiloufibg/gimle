import { createFileRoute } from "@tanstack/react-router";

import { AddonRoute } from "@/addons/addon-route";
import { SkaldPage } from "@/addons/skald/screen";
import { addonById } from "@/addons";

const addon = addonById("skald");

export const Route = createFileRoute("/skald")({
  head: () => ({
    meta: [
      { title: `${addon.title} — Gimlé Console` },
      { name: "description", content: addon.description },
      { property: "og:title", content: `${addon.title} — Gimlé Console` },
      { property: "og:description", content: addon.description },
    ],
  }),
  component: () => (
    <AddonRoute addon={addon}>
      <SkaldPage />
    </AddonRoute>
  ),
});
