package com.gimle.controlplane.admission;

/**
 * One {@link AdmissionPlugin}'s verdict on an {@link AdmissionRequest}: either the request may
 * proceed -- carrying forward the spec unchanged, or a mutated one, for the next plugin in the
 * chain to see -- or it's rejected outright with a human-readable reason that becomes the API
 * response body. Mirrors Kubernetes' own mutating-then-validating admission shape collapsed into
 * one ordered decision type, since nothing in this codebase yet needs the two phases kept
 * structurally separate.
 */
public sealed interface AdmissionDecision<T> {

  record Allow<T>(T spec) implements AdmissionDecision<T> {}

  record Reject<T>(String reason) implements AdmissionDecision<T> {}

  static <T> AdmissionDecision<T> allow(T spec) {
    return new Allow<>(spec);
  }

  static <T> AdmissionDecision<T> reject(String reason) {
    return new Reject<>(reason);
  }
}
