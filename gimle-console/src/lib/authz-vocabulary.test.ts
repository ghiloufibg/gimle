import { describe, expect, it } from "vitest";
import { optionsIncludingSelected, vocabularyOptions } from "./authz-vocabulary";
import { RESOURCE_KINDS } from "@/types";

describe("vocabularyOptions", () => {
  it("prefers what the control plane served, including kinds this build predates", () => {
    expect(vocabularyOptions(["DEPLOYMENT", "SOMETHING_NEW"], RESOURCE_KINDS)).toEqual([
      "DEPLOYMENT",
      "SOMETHING_NEW",
    ]);
  });

  it("falls back to the bundled list when the endpoint could not be read", () => {
    expect(vocabularyOptions(null, RESOURCE_KINDS)).toEqual([...RESOURCE_KINDS]);
  });

  it("treats an empty served list as unreachable, never as an empty picker", () => {
    expect(vocabularyOptions([], RESOURCE_KINDS)).toEqual([...RESOURCE_KINDS]);
  });

  it("copies rather than aliasing the fallback, so a caller cannot mutate it", () => {
    const options = vocabularyOptions(null, RESOURCE_KINDS);
    options.push("MUTATED");
    expect(RESOURCE_KINDS).not.toContain("MUTATED");
  });
});

describe("optionsIncludingSelected", () => {
  it("leaves the list alone when the selected value is already offered", () => {
    expect(optionsIncludingSelected(["A", "B"], "B")).toEqual(["A", "B"]);
  });

  it("appends a selected value the vocabulary does not list, so the row still shows its grant", () => {
    expect(optionsIncludingSelected(["A", "B"], "LEGACY_KIND")).toEqual(["A", "B", "LEGACY_KIND"]);
  });
});

describe("the bundled fallback vocabulary", () => {
  it("covers every kind the control plane's own ResourceKind enum declares", () => {
    // Mirrors com.gimle.core.authz.ResourceKind. The picker reads the live enum at runtime, so this
    // only pins the offline fallback -- but a fallback missing a kind is exactly the drift that
    // made whole resource kinds CLI-only before.
    expect(RESOURCE_KINDS).toEqual([
      "DEPLOYMENT",
      "JOB",
      "DAEMONSET",
      "STATEFULSET",
      "NODE",
      "TENANT",
      "CONFIG",
      "SECRET",
      "LOGS",
      "CERTIFICATE_REQUEST",
      "BOOTSTRAP_TOKEN",
      "ROLE",
      "ROLE_BINDING",
      "ACCOUNT",
      "AUDIT",
      "ARTIFACT",
      "SERVICE",
      "NETWORK_POLICY",
      "CONFIGMAP",
      "SECRETMAP",
      "LIMIT_RANGE",
      "FAULT",
      "KIND_DEFINITION",
      "CUSTOM_RESOURCE",
      "BACKUP",
      "ALERT_RULE",
    ]);
  });
});
