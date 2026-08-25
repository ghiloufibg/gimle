package com.gimle.mimir.manifest;

import java.util.List;

/**
 * The outcome of parsing an operator-submitted manifest: the spec itself plus any deprecation
 * warnings the parse produced (today, a {@code v1alpha1} manifest naming a local {@code
 * artifactPath}). Warnings travel with the result so the API layer can surface them back to the
 * submitting operator instead of leaving them in a server-side log nobody reads at apply time.
 */
public record ParsedManifest(WorkloadSpec spec, List<String> warnings) {

  public ParsedManifest {
    warnings = List.copyOf(warnings);
  }
}
