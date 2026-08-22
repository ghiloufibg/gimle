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

  it("remove DELETEs /services/{name}", async () => {
    const fetchMock = stubFetchSequence([() => okResponse()]);
    const repo = new HttpServicesRepository();

    await repo.remove("orders-web");

    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toBe("/services/orders-web");
    expect(init.method).toBe("DELETE");
  });
});
