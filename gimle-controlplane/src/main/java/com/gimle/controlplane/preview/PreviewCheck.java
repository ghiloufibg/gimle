package com.gimle.controlplane.preview;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One stage of a dry-run, named after the stage of a real submission it stands in for: {@code
 * rbac}, {@code manifest}, {@code artifact}, {@code admission}, {@code placement}.
 *
 * <p>{@code detail} on a failure is verbatim the message the real submission would have answered
 * with, never a reworded approximation -- a preview an operator is meant to trust has to be
 * comparable word for word against the request it predicts.
 */
public record PreviewCheck(String name, PreviewOutcome outcome, String detail) {

  public static PreviewCheck passed(String name, String detail) {
    return new PreviewCheck(name, PreviewOutcome.PASSED, detail);
  }

  public static PreviewCheck failed(String name, String detail) {
    return new PreviewCheck(name, PreviewOutcome.FAILED, detail);
  }

  public static PreviewCheck skipped(String name, String detail) {
    return new PreviewCheck(name, PreviewOutcome.SKIPPED, detail);
  }

  public Map<String, Object> toJson() {
    Map<String, Object> json = new LinkedHashMap<>();
    json.put("name", name);
    json.put("outcome", outcome.name());
    json.put("detail", detail);
    return json;
  }
}
