package com.gimle.controlplane.preview;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * What a workload submission would do, computed without proposing anything to the store.
 *
 * <p>{@code admitted} answers only "would the write be accepted": authorization, manifest kind/name
 * validation, artifact resolution, and the admission chain (tenant quota, LimitRange,
 * ConfigMap/SecretMap references, policy config). {@code wouldRespondStatus} is the HTTP status the
 * identical non-dry-run request would have answered with -- {@code 200} when admitted, otherwise
 * the very status the failing stage itself produces ({@code 400} for a manifest or artifact
 * problem, {@code 409} for an admission rejection) -- so a caller can map a predicted rejection
 * onto the same outcome the real request would have given it, rather than inventing a second
 * classification that could drift from the first.
 *
 * <p>{@code placement} is separate from {@code admitted} on purpose: an unplaceable replica never
 * rejects a submission, it merely fails to be scheduled until the cluster has room (see {@link
 * PlacementForecast}).
 */
public record DryRunVerdict(
    String kind,
    String name,
    Optional<String> tenantId,
    boolean admitted,
    int wouldRespondStatus,
    List<PreviewCheck> checks,
    Optional<PlacementForecast> placement) {

  public static final int ADMITTED_STATUS = 200;

  public DryRunVerdict {
    checks = List.copyOf(checks);
  }

  public static DryRunVerdict rejected(
      String kind,
      String name,
      Optional<String> tenantId,
      int wouldRespondStatus,
      List<PreviewCheck> checks) {
    return new DryRunVerdict(
        kind, name, tenantId, false, wouldRespondStatus, checks, Optional.empty());
  }

  public static DryRunVerdict admitted(
      String kind,
      String name,
      Optional<String> tenantId,
      List<PreviewCheck> checks,
      Optional<PlacementForecast> placement) {
    return new DryRunVerdict(kind, name, tenantId, true, ADMITTED_STATUS, checks, placement);
  }

  public Map<String, Object> toJson() {
    Map<String, Object> json = new LinkedHashMap<>();
    json.put("dryRun", true);
    json.put("kind", kind);
    json.put("name", name);
    json.put("tenantId", tenantId.orElse(null));
    json.put("admitted", admitted);
    json.put("wouldRespondStatus", wouldRespondStatus);
    List<Map<String, Object>> checkJson = new ArrayList<>();
    for (PreviewCheck check : checks) {
      checkJson.add(check.toJson());
    }
    json.put("checks", checkJson);
    placement.ifPresent(forecast -> json.put("placement", forecast.toJson()));
    return json;
  }
}
