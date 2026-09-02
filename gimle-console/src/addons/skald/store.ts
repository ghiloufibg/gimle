import { create } from "zustand";
import type { MetricsHistoryLine } from "@/types";
import { metricsHistoryRepo, servicesRepo } from "@/repositories";
import { describeApiError, storeErrorMessage } from "@/lib/api-error";
import { skaldDnsName, SKALD_FAILURES_METRIC, SKALD_STALENESS_METRIC } from "@/addons/skald/dns";

const RESPONDERS_KEY = "gimle.console.skald.responders";

/** How far back a responder's own gauges are looked for. A replica shipping on its usual interval
 * lands several readings inside this; nothing inside it means the replica has stopped shipping. */
const GAUGE_LOOKBACK_LINES = 200;

/** One name Skald would answer for, derived from the same `/services/*` reads Skald itself polls. */
export interface SkaldName {
  dnsName: string;
  serviceName: string;
  tenantId: string | null;
  /** How many `A` records the query would come back with -- distinct endpoint hosts, not
   * endpoints: two replicas on one node share one address and answer as a single A record. Zero is
   * the failure this screen catches. */
  addressCount: number;
  port: number | null;
  deploymentNames: string[];
  /** Set when the endpoint read for this Service failed, so a blank row is never read as "empty". */
  unreadable: string | null;
}

/** A Skald replica's own health, as its two shipped gauges report it. */
export interface SkaldResponder {
  /** The `host:port` the replica answers DNS on -- its own Muninn processId. */
  address: string;
  stalenessSeconds: number | null;
  consecutiveFailures: number | null;
  /** Timestamp of the newest gauge reading found, or null when nothing has been shipped. */
  lastReadingAt: string | null;
  error: string | null;
}

interface State {
  names: SkaldName[];
  responders: SkaldResponder[];
  loading: boolean;
  loaded: boolean;
  error: string | null;
  load(): Promise<void>;
  refresh(): Promise<void>;
  poll(): Promise<void>;
  addResponder(address: string): Promise<void>;
  removeResponder(address: string): void;
}

/** Responder addresses are an operator's own note of which replicas exist -- nothing enumerates
 * them, so they are remembered in this browser rather than re-typed on every visit. */
function loadResponderAddresses(): string[] {
  if (typeof window === "undefined") return [];
  try {
    const raw = window.localStorage.getItem(RESPONDERS_KEY);
    const parsed: unknown = raw ? JSON.parse(raw) : [];
    return Array.isArray(parsed) ? parsed.filter((a): a is string => typeof a === "string") : [];
  } catch {
    return [];
  }
}

function saveResponderAddresses(addresses: string[]): void {
  if (typeof window === "undefined") return;
  try {
    window.localStorage.setItem(RESPONDERS_KEY, JSON.stringify(addresses));
  } catch {
    // A browser refusing storage costs the operator a retyped address, nothing more.
  }
}

/** The newest reading of one gauge in a page of history lines. */
function latestGauge(lines: MetricsHistoryLine[], name: string): MetricsHistoryLine | null {
  let latest: MetricsHistoryLine | null = null;
  for (const line of lines) {
    if (line.name !== name) continue;
    if (latest === null || line.timestamp >= latest.timestamp) latest = line;
  }
  return latest;
}

async function readResponder(address: string): Promise<SkaldResponder> {
  try {
    const page = await metricsHistoryRepo.fetchPage({
      target: { processKind: "SKALD", processId: address },
      cursor: null,
      limit: GAUGE_LOOKBACK_LINES,
    });
    const staleness = latestGauge(page.lines, SKALD_STALENESS_METRIC);
    const failures = latestGauge(page.lines, SKALD_FAILURES_METRIC);
    return {
      address,
      stalenessSeconds: staleness?.measurements.VALUE ?? null,
      consecutiveFailures: failures?.measurements.VALUE ?? null,
      lastReadingAt: staleness?.timestamp ?? failures?.timestamp ?? null,
      error: null,
    };
  } catch (e) {
    return {
      address,
      stalenessSeconds: null,
      consecutiveFailures: null,
      lastReadingAt: null,
      error: describeApiError(e),
    };
  }
}

async function readNames(): Promise<SkaldName[]> {
  const services = await servicesRepo.fetchAll();
  return Promise.all(
    services.map(async (service): Promise<SkaldName> => {
      const base = {
        dnsName: skaldDnsName(service),
        serviceName: service.name,
        tenantId: service.tenantId ?? null,
        deploymentNames: service.deploymentNames,
      };
      try {
        const endpoints = await servicesRepo.fetchEndpoints(service.name, service.tenantId);
        return {
          ...base,
          addressCount: new Set(endpoints.endpoints.map((e) => e.host)).size,
          // Every endpoint of one Service answers on the same port, so the first one names it.
          port: endpoints.endpoints[0]?.port ?? null,
          unreadable: null,
        };
      } catch (e) {
        return {
          ...base,
          addressCount: 0,
          port: null,
          unreadable: describeApiError(e),
        };
      }
    }),
  );
}

export const useSkaldStore = create<State>((set, get) => ({
  names: [],
  responders: loadResponderAddresses().map((address) => ({
    address,
    stalenessSeconds: null,
    consecutiveFailures: null,
    lastReadingAt: null,
    error: null,
  })),
  loading: false,
  loaded: false,
  error: null,
  async load() {
    if (get().loading) return;
    set({ loading: true, error: null });
    try {
      const [names, responders] = await Promise.all([
        readNames(),
        Promise.all(get().responders.map((r) => readResponder(r.address))),
      ]);
      set({ names, responders, loading: false, loaded: true });
    } catch (e) {
      set({ loading: false, error: storeErrorMessage(e) });
    }
  },
  async refresh() {
    set({ loaded: false });
    await get().load();
  },
  /** The screen's auto-refresh read: no `loading` flag, so the last good table stays visible if a
   * poll fails. */
  async poll() {
    if (get().loading) return;
    try {
      const [names, responders] = await Promise.all([
        readNames(),
        Promise.all(get().responders.map((r) => readResponder(r.address))),
      ]);
      set({ names, responders, loaded: true, error: null });
    } catch (e) {
      set({ error: storeErrorMessage(e) });
    }
  },
  async addResponder(address) {
    const trimmed = address.trim();
    if (trimmed === "" || get().responders.some((r) => r.address === trimmed)) return;
    const responder = await readResponder(trimmed);
    const responders = [...get().responders, responder];
    saveResponderAddresses(responders.map((r) => r.address));
    set({ responders });
  },
  removeResponder(address) {
    const responders = get().responders.filter((r) => r.address !== address);
    saveResponderAddresses(responders.map((r) => r.address));
    set({ responders });
  },
}));
