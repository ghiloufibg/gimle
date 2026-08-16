package com.gimle.testkit.heimdall;

import java.util.function.Predicate;

/**
 * A property that must hold on every observed {@link ClusterView} for as long as a guard holds it
 * -- the "stays true throughout" half of the condition vocabulary, where {@link HeimdallCondition}
 * is the "eventually becomes true" half. Built via {@link Invariants}.
 */
public record Invariant(String description, Predicate<ClusterView> holds) {}
