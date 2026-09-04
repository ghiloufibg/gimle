import { afterEach, describe, expect, it, vi } from "vitest";
import { HttpServicesRepository } from "./services";
import { jsonResponse, okResponse, stubFetchSequence } from "./testUtil";

afterEach(() => {
  vi.unstubAllGlobals();
});

describe("HttpServicesRepository", () => {
  it("fetchAll GETs /services", async () => {
    const fetchMock = stubFetchSequence([
      () =>
        jsonResponse([
          { name: "orders-web", deploymentNames: ["orders-service"], port: 8080, targetPort: 8080 },
        ]),
    ]);
    const repo = new HttpServicesRepository();

    const services = await repo.fetchAll();

    expect(services).toHaveLength(1);
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toBe("/services");
    expect(init.method).toBe("GET");
  });

  it("fetchOne GETs /services/{name}, url-encoding the segment", async () => {
    const fetchMock = stubFetchSequence([
      () => jsonResponse({ name: "a/b", deploymentNames: ["d"], port: 80, targetPort: 80 }),
    ]);
    const repo = new HttpServicesRepository();

    await repo.fetchOne("a/b");

    const [url] = fetchMock.mock.calls[0] as [string];
    expect(url).toBe("/services/a%2Fb");
  });

  it("fetchEndpoints GETs /services/{name}/endpoints", async () => {
    const fetchMock = stubFetchSequence([
      () =>
        jsonResponse({
          name: "orders-web",
          port: 8080,
          targetPort: 8080,
          endpoints: [{ host: "10.0.1.4", port: 8080 }],
        }),
    ]);
    const repo = new HttpServicesRepository();

    const ep = await repo.fetchEndpoints("orders-web");

    expect(ep.endpoints).toHaveLength(1);
    const [url] = fetchMock.mock.calls[0] as [string];
    expect(url).toBe("/services/orders-web/endpoints");
  });

  it("save POSTs the full spec to the bare /services collection", async () => {
    const fetchMock = stubFetchSequence([() => okResponse()]);
    const repo = new HttpServicesRepository();

    await repo.save({
      name: "orders-web",
      tenantId: "acme",
      deploymentNames: ["orders-service"],
      port: 8080,
      targetPort: 8080,
    });

    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toBe("/services");
    expect(init.method).toBe("POST");
    expect(JSON.parse(init.body as string)).toEqual({
      name: "orders-web",
      tenantId: "acme",
      deploymentNames: ["orders-service"],
      port: 8080,
      targetPort: 8080,
    });
  });

  it("save omits targetPort entirely when the spec declares none", async () => {
    const fetchMock = stubFetchSequence([() => okResponse()]);
    const repo = new HttpServicesRepository();

    await repo.save({
      name: "orders-web",
      deploymentNames: ["orders-service"],
      port: 8080,
    });

    const [, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(JSON.parse(init.body as string)).toEqual({
      name: "orders-web",
      deploymentNames: ["orders-service"],
      port: 8080,
    });
  });

  it("fetchAll accepts a service the control plane sent no targetPort for", async () => {
    stubFetchSequence([
      () => jsonResponse([{ name: "orders-web", deploymentNames: ["d"], port: 8080 }]),
    ]);
    const repo = new HttpServicesRepository();

    const [service] = await repo.fetchAll();

    expect(service.targetPort).toBeUndefined();
  });

  it("save returns the control plane's X-Gimle-Warning header, not just the bare 'ok' body", async () => {
    stubFetchSequence([
      () =>
        new Response("ok", {
          status: 200,
          headers: {
            "X-Gimle-Warning":
              "service orders-web fronts deployment(s) [orders-service] already fronted by service orders-alias in the same tenant -- both names route to the same instances",
          },
        }),
    ]);
    const repo = new HttpServicesRepository();

    const warning = await repo.save({
      name: "orders-web",
      tenantId: "acme",
      deploymentNames: ["orders-service"],
      port: 8080,
    });

    expect(warning).toContain("already fronted by service orders-alias");
  });

  it("save resolves to null when the control plane attaches no warning", async () => {
    stubFetchSequence([() => okResponse()]);
    const repo = new HttpServicesRepository();

    const warning = await repo.save({
      name: "orders-web",
      deploymentNames: ["orders-service"],
      port: 8080,
    });

    expect(warning).toBeNull();
  });

  it("remove DELETEs /services/{name}", async () => {
    const fetchMock = stubFetchSequence([() => okResponse()]);
    const repo = new HttpServicesRepository();

    await repo.remove("orders-web");

    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toBe("/services/orders-web");
    expect(init.method).toBe("DELETE");
  });
});
