import { createFileRoute } from "@tanstack/react-router";

import { PageContainer, PageHeader, Panel } from "@/components/page-shell";

const DESCRIPTION =
  "Fafnir's asymmetric sealing key: fetch the public key, rotate it, retire an old one.";

export const Route = createFileRoute("/seal")({
  head: () => ({
    meta: [
      { title: "Seal Keys — Gimlé Console" },
      { name: "description", content: DESCRIPTION },
      { property: "og:title", content: "Seal Keys — Gimlé Console" },
      { property: "og:description", content: DESCRIPTION },
      { property: "og:type", content: "website" },
      { name: "twitter:card", content: "summary" },
    ],
  }),
  component: SealPage,
});

function SealPage() {
  return (
    <PageContainer>
      <PageHeader title="Seal Keys" subtitle={DESCRIPTION} />
      <Panel title="Sealing key">
        <div className="px-4 py-10 text-center text-xs text-muted-foreground">
          No data loaded yet.
        </div>
      </Panel>
    </PageContainer>
  );
}
