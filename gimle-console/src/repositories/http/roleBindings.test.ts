import { afterEach, describe, expect, it, vi } from "vitest";
import { HttpRoleBindingsRepository } from "./roleBindings";
import { jsonResponse, okResponse, stubFetchSequence } from "./testUtil";

afterEach(() => {
  vi.unstubAllGlobals();
});

describe("HttpRoleBindingsRepository", () => {
  it("fetchAll GETs /rolebindings", async () => {
    const fetchMock = stubFetchSequence([() => jsonResponse([])]);
    const repo = new HttpRoleBindingsRepository();

    await repo.fetchAll();

    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toBe("/rolebindings");
    expect(init.method).toBe("GET");
  });

  it("save PUTs {subject, roleName} to /rolebindings/{id}", async () => {
    const fetchMock = stubFetchSequence([() => okResponse()]);
    const repo = new HttpRoleBindingsRepository();

    await repo.save("b1", { subject: "user:admin", roleName: "cluster-admin" });

    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toBe("/rolebindings/b1");
    expect(init.method).toBe("PUT");
    expect(JSON.parse(init.body as string)).toEqual({
      subject: "user:admin",
      roleName: "cluster-admin",
    });
  });

  it("remove DELETEs /rolebindings/{id}", async () => {
    const fetchMock = stubFetchSequence([() => okResponse()]);
    const repo = new HttpRoleBindingsRepository();

    await repo.remove("b1");

    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toBe("/rolebindings/b1");
    expect(init.method).toBe("DELETE");
  });
});
