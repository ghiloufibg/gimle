import { createFileRoute } from "@tanstack/react-router";

import { AddonRoute } from "@/addons/addon-route";
import { ApplicationDetailPage } from "@/addons/applications/detail";
import { addonById } from "@/addons";

const addon = addonById("applications");

/** The tenant travels in the query string rather than the path: a workload's identity is the
 * `(tenant, name)` pair, and a by-name read with no tenant resolves only the untenanted namespace,
 * so a shared link to a tenanted application would otherwise land on nothing. */
export const Route = createFileRoute("/apps/$kind/$name")({
  validateSearch: (search: Record<string, unknown>): { tenant?: string } =>
    typeof search.tenant === "string" && search.tenant !== "" ? { tenant: search.tenant } : {},
  head: ({ params }) => ({
    meta: [
      { title: `${params.name} — ${addon.title} — Gimlé Console` },
      { name: "description", content: `${params.kind} ${params.name} as an application.` },
      { property: "og:title", content: `${params.name} — Gimlé Console` },
      { property: "og:description", content: `${params.kind} ${params.name} as an application.` },
    ],
  }),
  component: ApplicationDetail,
});

function ApplicationDetail() {
  const { kind, name } = Route.useParams();
  const { tenant } = Route.useSearch();
  return (
    <AddonRoute addon={addon}>
      <ApplicationDetailPage kind={kind} name={name} tenant={tenant ?? null} />
    </AddonRoute>
  );
}
