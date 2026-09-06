import { describe, expect, it } from "vitest";
import { QueryClient } from "@tanstack/react-query";
import { RouterContextProvider, createMemoryHistory, createRouter } from "@tanstack/react-router";
import { renderToStaticMarkup } from "react-dom/server";
import { routeTree } from "@/routeTree.gen";
import type { ModuleInstance } from "@/types";
import { InstancesTable } from "./instances-table";

// Rendered to static markup rather than into a DOM: the hrefs this table builds are attributes of
// the initial render, which react-dom/server produces on its own under this project's
// node-environment vitest config. The router only has to be present for `Link` to resolve a
// target against the real route tree -- nothing here navigates.
function renderRows(rows: ModuleInstance[]): string {
  const router = createRouter({
    routeTree,
    context: { queryClient: new QueryClient() },
    history: createMemoryHistory({ initialEntries: ["/instances"] }),
  });
  return renderToStaticMarkup(
    <RouterContextProvider router={router}>
      <InstancesTable
        rows={rows}
        filters={{}}
        onFiltersChange={() => {}}
        hasMore={false}
        loading={false}
        onLoadMore={() => {}}
      />
    </RouterContextProvider>,
  );
}

const ROW: ModuleInstance = {
  deploymentName: "greeter",
  instanceIndex: 0,
  moduleId: { name: "com.example.greeter", version: "1.0.0" },
  artifactPath: "",
  tenantId: null,
  nodeId: "node-1",
  lifecycleState: "ACTIVE",
  alive: true,
  ready: true,
  requestRatePerSecond: 0,
  errorRatePerSecond: 0,
  queueDepth: 0,
  cpuMillicoresUsed: 0,
  memoryBytesUsed: 0,
  workerId: "worker-7",
};

describe("InstancesTable worker column", () => {
  it("links a reported worker to that worker's own metrics", () => {
    const html = renderRows([ROW]);
    expect(html).toContain("processKind=WORKER&amp;processId=node-1%3Aworker-7");
  });

  it("leaves a row whose worker isn't known yet as a dash, with nothing to follow", () => {
    const html = renderRows([{ ...ROW, workerId: null }]);
    expect(html).not.toContain("processKind=WORKER");
  });
});
