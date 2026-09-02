import { createFileRoute } from "@tanstack/react-router";

import { AddonRoute } from "@/addons/addon-route";
import { ApplicationsPage } from "@/addons/applications/screen";
import { addonById } from "@/addons";

const addon = addonById("applications");

export const Route = createFileRoute("/apps/")({
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
      <ApplicationsPage />
    </AddonRoute>
  ),
});
