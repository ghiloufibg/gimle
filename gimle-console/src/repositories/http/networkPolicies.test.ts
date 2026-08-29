import { afterEach, describe, expect, it, vi } from "vitest";
import { HttpNetworkPoliciesRepository } from "./networkPolicies";
import { jsonResponse, okResponse, stubFetchSequence } from "./testUtil";

afterEach(() => {
  vi.unstubAllGlobals();
});

describe("HttpNetworkPoliciesRepository", () => {
  it("fetchAll GETs /networkpolicies", async () => {
    const fetchMock = stubFetchSequence([
      () =>
        jsonResponse([
          {
            name: "acme-billing-policy",
            tenantId: "acme",
            deploymentNames: [],
            allowedCallerTenantIds: ["partner"],
          },
        ]),
    ]);
    const repo = new HttpNetworkPoliciesRepository();

    const policies = await repo.fetchAll();

    expect(policies).toHaveLength(1);
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toBe("/networkpolicies");
    expect(init.method).toBe("GET");
  });

  it("fetchOne GETs /networkpolicies/{name}?tenant=<id>, url-encoding both segments", async () => {
    const fetchMock = stubFetchSequence([
      () =>
        jsonResponse({
          name: "a/b",
          tenantId: "acme/co",
          deploymentNames: [],
          allowedCallerTenantIds: [],
        }),
    ]);
    const repo = new HttpNetworkPoliciesRepository();

    await repo.fetchOne("a/b", "acme/co");

    const [url] = fetchMock.mock.calls[0] as [string];
    expect(url).toBe("/networkpolicies/a%2Fb?tenant=acme%2Fco");
  });

  it("save POSTs the full spec to the bare /networkpolicies collection", async () => {
    const fetchMock = stubFetchSequence([() => okResponse()]);
    const repo = new HttpNetworkPoliciesRepository();

    await repo.save({
      name: "acme-billing-policy",
      tenantId: "acme",
      deploymentNames: ["billing-primary"],
      allowedCallerTenantIds: ["partner"],
    });

    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toBe("/networkpolicies");
    expect(init.method).toBe("POST");
    expect(JSON.parse(init.body as string)).toEqual({
      name: "acme-billing-policy",
      tenantId: "acme",
      deploymentNames: ["billing-primary"],
      allowedCallerTenantIds: ["partner"],
    });
  });

  it("remove DELETEs /networkpolicies/{name}?tenant=<id> -- tenantId is always required, never omitted", async () => {
    const fetchMock = stubFetchSequence([() => okResponse()]);
    const repo = new HttpNetworkPoliciesRepository();

    await repo.remove("acme-billing-policy", "acme");

    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toBe("/networkpolicies/acme-billing-policy?tenant=acme");
    expect(init.method).toBe("DELETE");
  });
});
