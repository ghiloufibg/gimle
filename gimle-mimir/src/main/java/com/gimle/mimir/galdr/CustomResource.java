package com.gimle.mimir.galdr;

import java.util.Optional;

/**
 * One stored instance of a custom kind. The store never looks inside {@code specJson}/{@code
 * statusJson}: both are canonical JSON produced by the control plane after validation and
 * defaulting (spec) or reported verbatim by an operator (status) -- the same "policy lives in the
 * owning process, never in the store" posture the secret store already takes. {@code generation} is
 * bumped by the store on every accepted spec put and never by a status put, so an operator's {@code
 * observedGeneration} comparison stays meaningful; like {@link KindDefinitionSpec}, the stored
 * value is store-assigned, with the proposer's copy carrying only the compare-and-set expectation.
 */
public record CustomResource(
    String kindName,
    String name,
    Optional<String> tenantId,
    byte[] specJson,
    byte[] statusJson,
    long generation) {

  public CustomResource {
    if (specJson == null) {
      throw new IllegalArgumentException("specJson must not be null");
    }
    if (statusJson == null) {
      throw new IllegalArgumentException("statusJson must not be null");
    }
    specJson = specJson.clone();
    statusJson = statusJson.clone();
  }

  @Override
  public byte[] specJson() {
    return specJson.clone();
  }

  @Override
  public byte[] statusJson() {
    return statusJson.clone();
  }
}
