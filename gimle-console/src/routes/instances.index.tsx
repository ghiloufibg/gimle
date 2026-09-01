import { createFileRoute } from "@tanstack/react-router";
import { useEffect, useState } from "react";
import { useAutoRefresh } from "@/hooks/use-auto-refresh";
import { useInstancesStore } from "@/stores/useInstancesStore";
import { PageContainer, PageHeader } from "@/components/page-shell";
import { InstancesTable, type InstancesTableFilters } from "@/components/instances-table";
import { Button } from "@/components/ui/button";
import type { LifecycleState } from "@/types";

export const Route = createFileRoute("/instances/")({
  head: () => ({
    meta: [
      { title: "Instances — Gimlé Console" },
      { name: "description", content: "Every running jar across the cluster." },
      { property: "og:title", content: "Instances — Gimlé Console" },
      { property: "og:description", content: "Every running jar across the cluster." },
    ],
  }),
  component: InstancesList,
});

function InstancesList() {
  const { items, hasMore, loading, loadFirstPage, loadMore, refresh, setFilter, poll } =
    useInstancesStore();
  const [filters, setFilters] = useState<InstancesTableFilters>({});

  useEffect(() => {
    // Reset any node/tenant-locked filter left over from a detail page.
    setFilter({}).then(() => {
      if (items.length === 0) loadFirstPage();
    });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // poll() re-reads under whatever filter is currently set, so an auto-refresh never widens or
  // narrows the table the operator is looking at.
  useAutoRefresh(poll);

  function onFiltersChange(next: InstancesTableFilters) {
    setFilters(next);
    setFilter({
      deployment: next.deployment,
      nodeId: next.nodeId,
      tenantId: next.tenantId,
      lifecycle:
        next.lifecycle && next.lifecycle !== "ALL" ? (next.lifecycle as LifecycleState) : undefined,
    });
  }

  return (
    <PageContainer>
      <PageHeader
        title="Instances"
        subtitle="The cluster-wide list of every running module instance (equivalent to Kubernetes pods)."
        actions={
          <Button variant="outline" size="sm" onClick={() => refresh()} disabled={loading}>
            Refresh
          </Button>
        }
      />
      <InstancesTable
        rows={items}
        filters={filters}
        onFiltersChange={onFiltersChange}
        hasMore={hasMore}
        loading={loading}
        onLoadMore={loadMore}
      />
    </PageContainer>
  );
}
