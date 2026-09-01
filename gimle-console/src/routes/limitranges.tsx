import { createFileRoute } from "@tanstack/react-router";

import { PageContainer, PageHeader, Panel } from "@/components/page-shell";

const DESCRIPTION = "Per-tenant min/max bounds on what any single workload may request or limit.";

export const Route = createFileRoute("/limitranges")({
  head: () => ({
    meta: [
      { title: "LimitRanges — Gimlé Console" },
      { name: "description", content: DESCRIPTION },
      { property: "og:title", content: "LimitRanges — Gimlé Console" },
      { property: "og:description", content: DESCRIPTION },
      { property: "og:type", content: "website" },
      { name: "twitter:card", content: "summary" },
    ],
  }),
  component: LimitRangesPage,
});

function LimitRangesPage() {
  return (
    <PageContainer>
      <PageHeader title="LimitRanges" subtitle={DESCRIPTION} />
      <Panel title="Limit ranges">
        <div className="px-4 py-10 text-center text-xs text-muted-foreground">
          No data loaded yet.
        </div>
      </Panel>
    </PageContainer>
  );
}
