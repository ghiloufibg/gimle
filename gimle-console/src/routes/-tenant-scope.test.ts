import { describe, expect, it } from "vitest";
import { Route as ConfigRoute } from "./config";
import { Route as ConfigMapsRoute } from "./configmaps";
import { Route as SecretMapsRoute } from "./secretmaps";
import { Route as SecretsRoute } from "./secrets";

// Only each route's own search-parameter contract is asserted here -- this project's vitest config
// is deliberately node-environment (see vitest.config.ts), so the JSX half of these screens (the
// picker navigating, the store following the resolved tenant) is exercised live in a real browser
// instead, not here.

type SearchValidator = (search: Record<string, unknown>) => unknown;

/**
 * The router's `validateSearch` option also admits validator objects and schema adapters, neither
 * of which any of these routes declares -- they all declare the plain function this narrowing
 * reads back, so it lives here once rather than at every assertion.
 */
function searchValidatorOf(route: { options: { validateSearch?: unknown } }): SearchValidator {
  return route.options.validateSearch as SearchValidator;
}

const TENANT_SCOPED = [
  ["Config", ConfigRoute],
  ["ConfigMaps", ConfigMapsRoute],
  ["Secrets", SecretsRoute],
  ["SecretMaps", SecretMapsRoute],
] as const;

describe.each(TENANT_SCOPED)("%s route", (_name, route) => {
  it("carries the tenant it is scoped to in the URL, so a link to one opens on that tenant", () => {
    expect(searchValidatorOf(route)({ tenant: "acme" })).toEqual({ tenant: "acme" });
  });

  it("scopes to nothing in particular when the URL names no tenant", () => {
    expect(searchValidatorOf(route)({})).toEqual({});
  });
});
