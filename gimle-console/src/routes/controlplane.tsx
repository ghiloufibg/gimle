import { createFileRoute, Link } from "@tanstack/react-router";
import { PageContainer, PageHeader } from "@/components/page-shell";
import { Button } from "@/components/ui/button";
import { StatusBadge } from "@/components/status";
import { FileText } from "lucide-react";

export const Route = createFileRoute("/controlplane")({
  head: () => ({
    meta: [
      { title: "Control plane — Gimlé Console" },
      { name: "description", content: "Control plane status and logs." },
      { property: "og:title", content: "Control plane — Gimlé Console" },
      { property: "og:description", content: "Control plane status and logs." },
    ],
  }),
  component: ControlPlane,
});

function ControlPlane() {
  return (
    <PageContainer>
      <PageHeader
        title="Control plane"
        subtitle="Cluster-wide scheduler, quota enforcer, and platform services."
      />
      <div className="grid grid-cols-2 md:grid-cols-4 gap-3 mb-4">
        <div className="rounded border border-border bg-card p-3">
          <div className="text-[10px] uppercase tracking-wider text-muted-foreground">
            Scheduler
          </div>
          <div className="mt-2">
            <StatusBadge variant="ok">running</StatusBadge>
          </div>
        </div>
        <div className="rounded border border-border bg-card p-3">
          <div className="text-[10px] uppercase tracking-wider text-muted-foreground">
            Quota enforcer
          </div>
          <div className="mt-2">
            <StatusBadge variant="ok">running</StatusBadge>
          </div>
        </div>
        <div className="rounded border border-border bg-card p-3">
          <div className="text-[10px] uppercase tracking-wider text-muted-foreground">
            Heartbeat worker
          </div>
          <div className="mt-2">
            <StatusBadge variant="ok">running</StatusBadge>
          </div>
        </div>
        <div className="rounded border border-border bg-card p-3">
          <div className="text-[10px] uppercase tracking-wider text-muted-foreground">
            Artifact resolver
          </div>
          <div className="mt-2">
            <StatusBadge variant="ok">running</StatusBadge>
          </div>
        </div>
      </div>
      <div className="flex gap-2">
        <Button size="sm" asChild>
          <Link to="/logs" search={{ kind: "controlplane", category: "PLATFORM" as const }}>
            <FileText className="h-4 w-4" />
            Platform logs
          </Link>
        </Button>
        <Button variant="outline" size="sm" asChild>
          <Link to="/logs" search={{ kind: "controlplane", category: "SYSTEM" as const }}>
            <FileText className="h-4 w-4" />
            System logs
          </Link>
        </Button>
      </div>
    </PageContainer>
  );
}
