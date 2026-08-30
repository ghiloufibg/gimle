package com.gimle.module.galdr;

import java.util.List;

/**
 * One reconciliation pass over the full current set of a kind's resources -- called by {@link
 * GaldrOperatorLoop} every tick with everything the operator's own RBAC binding lets it read, never
 * a delta. Absence is a valid state: a resource present last tick and missing now was deleted, and
 * whatever this operator materialized for it is this pass's job to notice and clean up. A thrown
 * exception fails only this tick, never the loop.
 */
@FunctionalInterface
public interface GaldrReconciler {

  void reconcile(List<GaldrResource> resources);
}
