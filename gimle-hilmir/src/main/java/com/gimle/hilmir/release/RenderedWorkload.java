package com.gimle.hilmir.release;

/**
 * One workload from a {@link BundleWorkload}, resolved to concrete text: every {@code ${values.*}}
 * reference substituted, then {@code kind}/{@code name} peeked out of the result -- {@code kind}
 * selects the control plane's own URL prefix (see {@link WorkloadKinds}), {@code name} identifies
 * the resource for pruning, waiting, status, and undeploy.
 */
record RenderedWorkload(String kind, String name, String yaml) {}
