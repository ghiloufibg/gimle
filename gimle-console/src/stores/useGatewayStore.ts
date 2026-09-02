import { create } from "zustand";
import type { DaemonSet, DaemonSetInstance } from "@/types";
import { configRepo, daemonSetsRepo, endpointsRepo, servicesRepo } from "@/repositories";
import { describeApiError, storeErrorMessage } from "@/lib/api-error";
import { ApiError } from "@/repositories/http/apiClient";
import {
  parseGatewayRoutes,
  routeTarget,
  type GatewayRoute,
  type GatewayRouteError,
} from "@/lib/gateway-routes";

/** The DaemonSet the gateway module is deployed as, and the tenant its config keys live under --
 * both fixed by gimle-gateway's own shipped manifest, not per-cluster settings. */
export const GATEWAY_DAEMONSET_NAME = "gimle-gateway";
export const GATEWAY_CONFIG_TENANT = "gimle-system";
export const GATEWAY_ROUTES_KEY = "gateway.routes";
export const GATEWAY_PORT_KEY = "gateway.port";

/**
 * What a route's declared target currently resolves to.
 *
 * `missing` is the finding this screen exists for: a route pointing at a Service or deployment that
 * does not exist, which the gateway only discovers when a request for that path arrives. `empty`
 * is its softer sibling -- the target exists but nothing live is behind it right now.
 *
 * `unresolvable` is the honest gap: a FABRIC route names an exported service interface, and the
 * control plane holds no view of the fabric registry, so nothing here can say whether anything
 * exports it. That is not a failure to report as one.
 */
export type RouteResolution =
  | { status: "live"; endpointCount: number }
  | { status: "empty"; detail: string }
  | { status: "missing"; detail: string }
  | { status: "unresolvable"; detail: string }
  | { status: "unknown"; detail: string };

export interface GatewayRouteRow {
  route: GatewayRoute;
  resolution: RouteResolution;
}

interface State {
  rows: GatewayRouteRow[];
  parseErrors: GatewayRouteError[];
  /** Null until loaded; false when tenant `gimle-system` carries no `gateway.routes` key at all. */
  routesConfigured: boolean | null;
  listenPort: string | null;
  instances: DaemonSetInstance[];
  /** False when no `gimle-gateway` DaemonSet is deployed -- the gateway is an optional extension. */
  deployed: boolean;
  loading: boolean;
  loaded: boolean;
  error: string | null;
  load(): Promise<void>;
  refresh(): Promise<void>;
  poll(): Promise<void>;
}

/** Instances a request can actually be served by right now. */
export function readyInstances(instances: DaemonSetInstance[]): DaemonSetInstance[] {
  return instances.filter((i) => i.observation.alive && i.observation.ready);
}

async function resolveRoute(
  route: GatewayRoute,
  serviceNames: Map<string, string | undefined>,
): Promise<RouteResolution> {
  switch (route.kind) {
    case "FABRIC":
      return {
        status: "unresolvable",
        detail: "fabric target, resolved in-worker at call time",
      };
    case "SERVICE": {
      if (!serviceNames.has(route.serviceName)) {
        return { status: "missing", detail: "no such Service" };
      }
      const endpoints = await servicesRepo.fetchEndpoints(
        route.serviceName,
        serviceNames.get(route.serviceName),
      );
      return endpoints.endpoints.length > 0
        ? { status: "live", endpointCount: endpoints.endpoints.length }
        : { status: "empty", detail: "Service has no live endpoint" };
    }
    case "VESSEL": {
      const endpoints = await endpointsRepo.fetch(route.deploymentName);
      if (endpoints.length === 0) {
        return { status: "missing", detail: "no such deployment, or nothing placed" };
      }
      // The same two things a gateway instance itself needs before it can proxy: a node address to
      // dial, and the named port actually reported by that instance.
      const reachable = endpoints.filter((e) => e.host !== null && e.ports[route.portName] > 0);
      return reachable.length > 0
        ? { status: "live", endpointCount: reachable.length }
        : { status: "empty", detail: `no instance reporting port ${route.portName}` };
    }
  }
}

async function readGateway(): Promise<
  Pick<State, "rows" | "parseErrors" | "routesConfigured" | "listenPort" | "instances" | "deployed">
> {
  const config = await configRepo.fetchPage({
    tenantId: GATEWAY_CONFIG_TENANT,
    cursor: null,
    // The system tenant's config is a handful of keys; one page is the whole of it.
    pageSize: 500,
  });
  const routesEntry = config.items.find((e) => e.key === GATEWAY_ROUTES_KEY);
  const listenPort = config.items.find((e) => e.key === GATEWAY_PORT_KEY)?.value ?? null;

  const daemonSet = await fetchGatewayDaemonSet();
  const { routes, errors } = parseGatewayRoutes(routesEntry?.value ?? "");

  const services = await servicesRepo.fetchAll();
  const serviceTenants = new Map(services.map((s) => [s.name, s.tenantId]));
  // Two routes may name the same target (an exact route beside a prefix one is a deliberate,
  // common shape), and resolving each independently would issue the same read twice on every
  // auto-refresh tick. One read per distinct target, shared by every route naming it.
  const resolutions = new Map<string, Promise<RouteResolution>>();
  const rows: GatewayRouteRow[] = await Promise.all(
    routes.map(async (route) => {
      const key = `${route.kind} ${routeTarget(route)}`;
      let resolution = resolutions.get(key);
      if (resolution === undefined) {
        // One target failing to resolve must not blank the whole table -- the rest of it is still
        // exactly what the gateway is serving.
        resolution = resolveRoute(route, serviceTenants).catch(
          (e): RouteResolution => ({ status: "unknown", detail: describeApiError(e) }),
        );
        resolutions.set(key, resolution);
      }
      return { route, resolution: await resolution };
    }),
  );

  return {
    rows,
    parseErrors: errors,
    routesConfigured: routesEntry !== undefined,
    listenPort,
    instances: daemonSet?.instances ?? [],
    deployed: daemonSet !== null,
  };
}

/** A 404 here means "the gateway extension isn't deployed on this cluster", which is a state to
 * show, not an error to report. Anything else is a real failure and propagates. */
async function fetchGatewayDaemonSet(): Promise<DaemonSet | null> {
  try {
    return await daemonSetsRepo.fetchOne(GATEWAY_DAEMONSET_NAME);
  } catch (e) {
    if (e instanceof ApiError && e.status === 404) return null;
    throw e;
  }
}

export const useGatewayStore = create<State>((set, get) => ({
  rows: [],
  parseErrors: [],
  routesConfigured: null,
  listenPort: null,
  instances: [],
  deployed: false,
  loading: false,
  loaded: false,
  error: null,
  async load() {
    if (get().loading) return;
    set({ loading: true, error: null });
    try {
      set({ ...(await readGateway()), loading: false, loaded: true });
    } catch (e) {
      set({ loading: false, error: storeErrorMessage(e) });
    }
  },
  async refresh() {
    set({ loaded: false });
    await get().load();
  },
  /** The screen's auto-refresh read: no `loading` flag, so nothing flickers while a poll is out
   * and the last good table stays visible if one fails. */
  async poll() {
    if (get().loading) return;
    try {
      set({ ...(await readGateway()), loaded: true, error: null });
    } catch (e) {
      set({ error: storeErrorMessage(e) });
    }
  },
}));
