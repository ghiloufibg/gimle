import { Link } from "@tanstack/react-router";
import type { ReactNode } from "react";

import { cn } from "@/lib/utils";
import { HEALTH_RAIL, HEALTH_STROKE } from "@/addons/applications/components/tone";
import type { Application } from "@/addons/applications/model";
import { layoutTree, type TreeNode, type TreeNodeLink } from "@/addons/applications/tree";

/** Card and lane geometry. The layout returns tier/row coordinates; these turn them into pixels. */
const CARD_WIDTH = 200;
const CARD_HEIGHT = 56;
const COLUMN_GAP = 70;
const ROW_HEIGHT = 72;

const COLUMN = CARD_WIDTH + COLUMN_GAP;

function Card({ node, children }: { node: TreeNode; children?: ReactNode }) {
  return (
    <div
      className={cn(
        "absolute flex flex-col justify-center gap-0.5 overflow-hidden border border-l-[3px] border-border bg-card px-2.5 py-1.5",
        node.health === null ? "border-l-status-muted" : HEALTH_RAIL[node.health],
      )}
      style={{
        left: node.tier * COLUMN,
        top: node.row * ROW_HEIGHT,
        width: CARD_WIDTH,
        height: CARD_HEIGHT,
      }}
    >
      <span className="hud-label truncate">{node.eyebrow}</span>
      <span className="truncate font-mono text-[11px] font-bold text-signal" title={node.title}>
        {node.title}
      </span>
      <span className="truncate font-mono text-[10px] text-muted-foreground" title={node.subtitle}>
        {node.subtitle}
      </span>
      {children}
    </div>
  );
}

/** Wraps a card in whichever link its resource has a screen for; an unlinked card renders plain. */
function LinkedCard({ node, link }: { node: TreeNode; link: TreeNodeLink }) {
  const inner = <Card node={node} />;
  const className = "contents";
  switch (link.to) {
    case "service":
      return (
        <Link to="/networking" className={className}>
          {inner}
        </Link>
      );
    case "node":
      return (
        <Link to="/nodes/$nodeId" params={{ nodeId: link.nodeId }} className={className}>
          {inner}
        </Link>
      );
    case "instance":
      return (
        <Link
          to="/instances/$name/$idx"
          params={{ name: link.deploymentName, idx: String(link.instanceIndex) }}
          className={className}
        >
          {inner}
        </Link>
      );
    case "application":
      return (
        <Link
          to="/apps/$kind/$name"
          params={{ kind: link.kindSlug, name: link.name }}
          search={link.tenantId ? { tenant: link.tenantId } : {}}
          className={className}
        >
          {inner}
        </Link>
      );
    case "workload":
      return (
        <WorkloadLink kind={link.kind} name={link.name}>
          {inner}
        </WorkloadLink>
      );
  }
}

/** Each kind's own detail screen lives at its own path, and only these five have one. */
function WorkloadLink({
  kind,
  name,
  children,
}: {
  kind: Application["kind"];
  name: string;
  children: ReactNode;
}) {
  switch (kind) {
    case "Deployment":
      return (
        <Link to="/deployments/$name" params={{ name }} className="contents">
          {children}
        </Link>
      );
    case "StatefulSet":
      return (
        <Link to="/statefulsets/$name" params={{ name }} className="contents">
          {children}
        </Link>
      );
    case "DaemonSet":
      return (
        <Link to="/daemonsets/$name" params={{ name }} className="contents">
          {children}
        </Link>
      );
    case "Job":
      return (
        <Link to="/jobs/$name" params={{ name }} className="contents">
          {children}
        </Link>
      );
    case "CronJob":
      return (
        <Link to="/cronjobs/$name" params={{ name }} className="contents">
          {children}
        </Link>
      );
    case "CustomResource":
      return <>{children}</>;
  }
}

export function ResourceTree({ app }: { app: Application }) {
  const layout = layoutTree(app);
  const width = layout.tiers * COLUMN - COLUMN_GAP;
  const height = layout.rows * ROW_HEIGHT - (ROW_HEIGHT - CARD_HEIGHT);
  const nodeById = new Map(layout.nodes.map((n) => [n.id, n]));

  const centre = (node: TreeNode) => ({
    x: node.tier * COLUMN,
    y: node.row * ROW_HEIGHT + CARD_HEIGHT / 2,
  });

  return (
    <div className="overflow-x-auto p-4">
      <div className="relative" style={{ width, height }}>
        <svg
          className="absolute inset-0 overflow-visible"
          width={width}
          height={height}
          aria-hidden
        >
          {layout.edges.map((edge) => {
            const from = nodeById.get(edge.from);
            const to = nodeById.get(edge.to);
            if (from === undefined || to === undefined) return null;
            const a = centre(from);
            const b = centre(to);
            const x1 = a.x + CARD_WIDTH;
            const x2 = b.x;
            const bend = (x2 - x1) / 2;
            return (
              <path
                key={`${edge.from}->${edge.to}`}
                d={`M${x1} ${a.y} C${x1 + bend} ${a.y}, ${x2 - bend} ${b.y}, ${x2} ${b.y}`}
                fill="none"
                strokeWidth={1.5}
                stroke={edge.health === null ? "var(--status-muted)" : HEALTH_STROKE[edge.health]}
              />
            );
          })}
        </svg>
        {layout.nodes.map((node) =>
          node.link === null ? (
            <Card key={node.id} node={node} />
          ) : (
            <LinkedCard key={node.id} node={node} link={node.link} />
          ),
        )}
      </div>
    </div>
  );
}
