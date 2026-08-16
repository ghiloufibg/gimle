package com.gimle.hilmir.release;

import java.util.Optional;

/**
 * One {@code workloads[]} entry: exactly one of {@code file} (a sibling path, resolved relative to
 * the bundle document's own directory) or {@code inlineManifest} (the raw YAML text of a {@code
 * manifest:} block) is present -- {@link BundleParser} rejects any entry with both or neither.
 */
public record BundleWorkload(Optional<String> file, Optional<String> inlineManifest) {}
