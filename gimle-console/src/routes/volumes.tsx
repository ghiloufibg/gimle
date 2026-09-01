import { createFileRoute } from "@tanstack/react-router";

import { PageContainer, PageHeader, Panel } from "@/components/page-shell";

const DESCRIPTION = "StatefulSet persistent volumes across every node, retained orphans included.";

export const Route = createFileRoute("/volumes")({
  head: () => ({
    meta: [
      { title: "Volumes — Gimlé Console" },
      { name: "description", content: DESCRIPTION },
      { property: "og:title", content: "Volumes — Gimlé Console" },
      { property: "og:description", content: DESCRIPTION },
      { property: "og:type", content: "website" },
      { name: "twitter:card", content: "summary" },
    ],
  }),
  component: VolumesPage,
});

function VolumesPage() {
  return (
    <PageContainer>
      <PageHeader title="Volumes" subtitle={DESCRIPTION} />
      <Panel title="Volumes">
        <div className="px-4 py-10 text-center text-xs text-muted-foreground">
          No data loaded yet.
        </div>
      </Panel>
    </PageContainer>
  );
}
