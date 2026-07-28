import { createFileRoute, Link } from "@tanstack/react-router";
import { useEffect } from "react";
import { useTenantsStore } from "@/stores/useTenantsStore";
import { PageContainer, PageHeader } from "@/components/page-shell";
import { Button } from "@/components/ui/button";
import { fmtBytes, fmtMillicores } from "@/lib/format";

export const Route = createFileRoute("/tenants/")({
  head: () => ({
    meta: [
      { title: "Tenants — Gimlé Console" },
      { name: "description", content: "Tenants and their quotas." },
      { property: "og:title", content: "Tenants — Gimlé Console" },
      { property: "og:description", content: "Tenants and their quotas." },
    ],
  }),
  component: TenantsList,
});

function TenantsList() {
  const { items, hasMore, loading, loadFirstPage, loadMore, refresh } = useTenantsStore();
  useEffect(() => {
    if (items.length === 0) loadFirstPage();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);
  return (
    <PageContainer>
      <PageHeader
        title="Tenants"
        subtitle={`${items.length} loaded`}
        actions={
          <Button variant="outline" size="sm" onClick={() => refresh()} disabled={loading}>
            Refresh
          </Button>
        }
      />
      <div className="overflow-x-auto rounded border border-border bg-card">
        <table className="w-full text-xs">
          <thead className="bg-muted/50 text-muted-foreground">
            <tr className="text-left">
              <th className="px-2 py-1.5 font-medium">Tenant</th>
              <th className="px-2 py-1.5 font-medium text-right">Max memory</th>
              <th className="px-2 py-1.5 font-medium text-right">Max CPU</th>
              <th className="px-2 py-1.5 font-medium text-right">Max instances</th>
            </tr>
          </thead>
          <tbody>
            {items.map((t) => (
              <tr key={t.id} className="border-t border-border hover:bg-muted/30">
                <td className="px-2 py-1.5 font-mono">
                  <Link to="/tenants/$id" params={{ id: t.id }} className="text-primary hover:underline">
                    {t.id}
                  </Link>
                </td>
                <td className="px-2 py-1.5 font-mono text-right">{fmtBytes(t.quota.maxMemoryBytes)}</td>
                <td className="px-2 py-1.5 font-mono text-right">{fmtMillicores(t.quota.maxCpuMillicores)}</td>
                <td className="px-2 py-1.5 font-mono text-right">{t.quota.maxInstances}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <div className="mt-3 flex justify-center">
        {hasMore ? (
          <Button variant="outline" size="sm" onClick={() => loadMore()} disabled={loading}>
            {loading ? "Loading…" : "Load more"}
          </Button>
        ) : (
          <span className="text-xs text-muted-foreground">— end of list —</span>
        )}
      </div>
    </PageContainer>
  );
}
