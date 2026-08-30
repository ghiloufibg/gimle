package com.gimle.mimir.galdr;

/**
 * One extra table column a {@link KindDefinitionSpec} declares for CLI/console listings: a header
 * ({@code MESSAGE}) and a dotted path into an instance's spec or status ({@code spec.message},
 * {@code status.timesSaid}). Resolution is a plain path walk over the stored JSON -- an unresolved
 * path renders an empty cell, never an error.
 */
public record PrintColumn(String name, String path) {}
