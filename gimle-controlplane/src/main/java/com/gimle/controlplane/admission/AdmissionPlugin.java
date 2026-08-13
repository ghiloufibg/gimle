package com.gimle.controlplane.admission;

/** One admission decision point, run in order as part of an {@link AdmissionChain}. */
public interface AdmissionPlugin<T> {

  AdmissionDecision<T> review(AdmissionRequest<T> request);
}
