package com.gimle.module.galdr;

import java.util.List;

/**
 * One reconciliation pass over the full current set of a kind's resources -- called by {@link
 * GaldrOperatorLoop} every tick with everything the operator's own RBAC binding lets it read, never
 * a delta. Absence is a valid state: a resource present last tick and missing now was deleted, and
 * whatever this operator materialized for it is this pass's job to notice and clean up. A thrown
 * exception fails only this tick, never the loop -- but it does abandon the rest of this tick's
 * list: every resource ordered after the one that threw waits a full poll interval (and hits the
 * same poison again next tick). An operator that must keep serving its healthy resources while one
 * is poisoned wraps its per-resource work in its own try/catch, the way the {@code
 * greeting-operator} example does.
 */
@FunctionalInterface
public interface GaldrReconciler {

  void reconcile(List<GaldrResource> resources);
}
