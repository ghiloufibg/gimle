import { create } from "zustand";

import {
  cronJobsRepo,
  customResourcesRepo,
  daemonSetsRepo,
  deploymentsRepo,
  jobsRepo,
  servicesRepo,
  statefulSetsRepo,
} from "@/repositories";
import { storeErrorMessage } from "@/lib/api-error";
import type { ControllerRevision, CustomResourceItem, KindDefinitionSummary } from "@/types";
import {
  buildApplications,
  NO_WORKLOADS,
  type ClusterWorkloads,
} from "@/addons/applications/build";
import type { Application } from "@/addons/applications/model";

/** One page big enough to hold every workload of a kind: none of these endpoints paginates
 * server-side, so this only caps what the client-side pager hands back in one call. */
const ALL = 10_000;

/** Which kinds carry a ControllerRevision history, and the repository that reads it. */
const REVISION_REPOS = {
  deployment: deploymentsRepo,
  statefulset: statefulSetsRepo,
  daemonset: daemonSetsRepo,
} as const;

export type RevisionedSlug = keyof typeof REVISION_REPOS;

export function isRevisioned(slug: string): slug is RevisionedSlug {
  return slug in REVISION_REPOS;
}

interface State {
  applications: Application[];
  /** Kinds whose own read failed this refresh, so a missing application is never read as absent. */
  partialFailures: string[];
  loading: boolean;
  loaded: boolean;
  error: string | null;
  revisions: ControllerRevision[];
  revisionsKey: string | null;
  load(): Promise<void>;
  refresh(): Promise<void>;
  poll(): Promise<void>;
  loadRevisions(slug: string, name: string, tenantId: string | null): Promise<void>;
  rollback(slug: string, name: string, tenantId: string | null, toRevision: number): Promise<void>;
}

/** Every custom kind's instances, read one kind at a time -- no endpoint lists them all. A kind
 * whose own read fails is reported rather than silently dropped: an application missing from this
 * screen must never be mistaken for one that does not exist. */
async function readCustomResources(): Promise<{
  kindDefinitions: KindDefinitionSummary[];
  customResources: CustomResourceItem[];
  failures: string[];
}> {
  const kindDefinitions = await customResourcesRepo.fetchKinds();
  const failures: string[] = [];
  const perKind = await Promise.all(
    kindDefinitions.map(async (definition) => {
      try {
        return await customResourcesRepo.fetchResources(definition.kindName);
      } catch {
        failures.push(definition.kindName);
        return [];
      }
    }),
  );
  return { kindDefinitions, customResources: perKind.flat(), failures };
}

async function readCluster(): Promise<{ workloads: ClusterWorkloads; failures: string[] }> {
  const page = { cursor: null, pageSize: ALL };
  const [deployments, statefulSets, daemonSets, jobs, cronJobs, services, custom] =
    await Promise.all([
      deploymentsRepo.fetchPage(page),
      statefulSetsRepo.fetchPage(page),
      daemonSetsRepo.fetchPage(page),
      jobsRepo.fetchPage(page),
      cronJobsRepo.fetchPage(page),
      servicesRepo.fetchAll(),
      readCustomResources(),
    ]);
  return {
    workloads: {
      deployments: deployments.items,
      statefulSets: statefulSets.items,
      daemonSets: daemonSets.items,
      jobs: jobs.items,
      cronJobs: cronJobs.items,
      services,
      kindDefinitions: custom.kindDefinitions,
      customResources: custom.customResources,
    },
    failures: custom.failures,
  };
}

function revisionsKeyOf(slug: string, name: string, tenantId: string | null): string {
  return `${slug}/${tenantId ?? ""}/${name}`;
}

export const useApplicationsStore = create<State>((set, get) => ({
  applications: buildApplications(NO_WORKLOADS),
  partialFailures: [],
  loading: false,
  loaded: false,
  error: null,
  revisions: [],
  revisionsKey: null,

  async load() {
    if (get().loading) return;
    set({ loading: true, error: null });
    try {
      const { workloads, failures } = await readCluster();
      set({
        applications: buildApplications(workloads),
        partialFailures: failures,
        loading: false,
        loaded: true,
      });
    } catch (e) {
      set({ loading: false, error: storeErrorMessage(e) });
    }
  },

  async refresh() {
    set({ loaded: false });
    await get().load();
  },

  /** The screen's auto-refresh read: never raises `loading`, so nothing flickers or disables under
   * the pointer, and a failed poll leaves the last good list on screen with the reason beside it. */
  async poll() {
    if (get().loading) return;
    try {
      const { workloads, failures } = await readCluster();
      set({
        applications: buildApplications(workloads),
        partialFailures: failures,
        loaded: true,
        error: null,
      });
    } catch (e) {
      set({ error: storeErrorMessage(e) });
    }
  },

  /** Revision history exists only for the three revisioned kinds; asking for any other kind's
   * clears what is on screen rather than leaving the previous application's history under it. */
  async loadRevisions(slug, name, tenantId) {
    const key = revisionsKeyOf(slug, name, tenantId);
    if (!isRevisioned(slug)) {
      set({ revisions: [], revisionsKey: key });
      return;
    }
    set({ revisions: [], revisionsKey: key });
    try {
      const revisions = await REVISION_REPOS[slug].fetchRevisions(name, tenantId);
      // A slow response arriving after the operator has navigated on belongs to nothing on screen.
      if (get().revisionsKey !== key) return;
      set({ revisions });
    } catch (e) {
      if (get().revisionsKey !== key) return;
      set({ error: storeErrorMessage(e) });
    }
  },

  async rollback(slug, name, tenantId, toRevision) {
    if (!isRevisioned(slug)) return;
    await REVISION_REPOS[slug].rollback(name, toRevision, tenantId);
    // Re-read the whole cluster rather than patching one application: a rollback re-runs admission,
    // which can change placement, quota standing and endpoints together.
    await get().poll();
    await get().loadRevisions(slug, name, tenantId);
  },
}));
