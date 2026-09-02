import { describe, expect, it } from "vitest";
import {
  parseGatewayRoutes,
  routePathDisplay,
  routeTarget,
  type ServiceRoute,
  type VesselRoute,
} from "./gateway-routes";

describe("parseGatewayRoutes", () => {
  it("parses one route of each kind", () => {
    const { routes, errors } = parseGatewayRoutes(
      [
        "FABRIC /greet com.gimle.examples.greeter.Greeter 1 greet STRING",
        "VESSEL /static assets-vessel HTTP_PORT",
        "SERVICE /api/orders orders-api",
      ].join("\n"),
    );
    expect(errors).toEqual([]);
    expect(routes.map((r) => r.kind)).toEqual(["FABRIC", "VESSEL", "SERVICE"]);
    expect(routes.map((r) => r.line)).toEqual([1, 2, 3]);
    expect((routes[2] as ServiceRoute).serviceName).toBe("orders-api");
  });

  it("skips blank lines and comments without consuming line numbers", () => {
    const { routes } = parseGatewayRoutes("# a table\n\nSERVICE /api/orders orders-api\n");
    expect(routes).toHaveLength(1);
    expect(routes[0].line).toBe(3);
  });

  it("reads a HOST segment as a host constraint, leaving unconstrained routes null", () => {
    const { routes } = parseGatewayRoutes(
      "HOST shop.example.com SERVICE /checkout checkout-api\nSERVICE /api/orders orders-api",
    );
    expect(routes[0].host).toBe("shop.example.com");
    expect(routes[1].host).toBeNull();
  });

  it("reads a trailing /* as prefix matching, normalizing a bare catch-all to the root", () => {
    const { routes } = parseGatewayRoutes(
      "VESSEL /api/* api-vessel HTTP_PORT\nSERVICE /* fallback",
    );
    expect(routes[0]).toMatchObject({ path: "/api", prefix: true });
    expect(routes[1]).toMatchObject({ path: "/", prefix: true });
    expect(routePathDisplay(routes[0])).toBe("/api/*");
    expect(routePathDisplay(routes[1])).toBe("/*");
  });

  it("rejects prefix matching on a FABRIC route", () => {
    const { routes, errors } = parseGatewayRoutes("FABRIC /greet/* a.B 1 greet NONE");
    expect(routes).toEqual([]);
    expect(errors[0].message).toContain("do not support prefix matching");
  });

  it("reports a malformed line and keeps parsing the rest of the table", () => {
    const { routes, errors } = parseGatewayRoutes(
      ["SERVICE /api/orders", "SERVICE /api/ledger ledger-worker"].join("\n"),
    );
    expect(errors).toHaveLength(1);
    expect(errors[0]).toMatchObject({ line: 1, text: "SERVICE /api/orders" });
    expect(errors[0].message).toContain("expected 3 fields");
    expect(routes).toHaveLength(1);
  });

  it.each([
    ["MYSTERY /x y", "unknown route kind"],
    ["HOST", "HOST must be followed by a hostname"],
    ["HOST shop.example.com", "missing route kind after HOST"],
    ["SERVICE api/orders orders-api", "must start with '/'"],
    ["FABRIC /greet a.B x greet NONE", "majorVersion must be an integer"],
    ["FABRIC /greet a.B 1 greet WIDGET", "paramType must be one of"],
    ["VESSEL /api//* api-vessel HTTP_PORT", "must not end with '/'"],
  ])("rejects %s", (line, expected) => {
    const { errors } = parseGatewayRoutes(line);
    expect(errors[0].message).toContain(expected);
  });

  it("rejects a second route at the same path, host and match mode", () => {
    const { routes, errors } = parseGatewayRoutes(
      "SERVICE /api/orders orders-api\nSERVICE /api/orders other-api",
    );
    expect(routes).toHaveLength(1);
    expect(errors[0].message).toContain("duplicate route path '/api/orders'");
  });

  it("allows the same path under a different host, and an exact route beside a prefix one", () => {
    const { routes, errors } = parseGatewayRoutes(
      [
        "SERVICE /api/orders orders-api",
        "HOST shop.example.com SERVICE /api/orders shop-orders-api",
        "SERVICE /api/orders/* orders-subtree",
      ].join("\n"),
    );
    expect(errors).toEqual([]);
    expect(routes).toHaveLength(3);
  });
});

describe("routeTarget", () => {
  it("names each kind's target", () => {
    const { routes } = parseGatewayRoutes(
      [
        "FABRIC /greet com.gimle.examples.greeter.Greeter 1 greet STRING",
        "VESSEL /static assets-vessel HTTP_PORT",
        "SERVICE /api/orders orders-api",
      ].join("\n"),
    );
    expect(routeTarget(routes[0])).toBe("com.gimle.examples.greeter.Greeter v1 greet()");
    expect(routeTarget(routes[1] as VesselRoute)).toBe("assets-vessel (HTTP_PORT)");
    expect(routeTarget(routes[2])).toBe("orders-api");
  });
});
