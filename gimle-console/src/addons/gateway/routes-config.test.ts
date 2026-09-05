import { describe, expect, it } from "vitest";
import {
  routeKey,
  routePathDisplay,
  routeTarget,
  toGatewayRoutes,
  type Ingress,
  type ServiceRoute,
  type VesselRoute,
} from "./routes-config";

function ingress(name: string, routes: Ingress["routes"]): Ingress {
  return { name, tenantId: "gimle-system", version: 0, routes };
}

describe("toGatewayRoutes", () => {
  it("flattens one route of each kind, keeping its declaring ingress", () => {
    const routes = toGatewayRoutes([
      ingress("edge", [
        {
          kind: "FABRIC",
          path: "/greet",
          interfaceName: "com.gimle.examples.greeter.Greeter",
          majorVersion: 1,
          methodName: "greet",
          paramType: "STRING",
        },
        { kind: "VESSEL", path: "/static", deploymentName: "assets-vessel", portName: "HTTP_PORT" },
        { kind: "SERVICE", path: "/api/orders", serviceName: "orders-api" },
      ]),
    ]);

    expect(routes.map((r) => r.kind)).toEqual(["FABRIC", "VESSEL", "SERVICE"]);
    expect(routes.map((r) => r.ingressName)).toEqual(["edge", "edge", "edge"]);
    expect((routes[2] as ServiceRoute).serviceName).toBe("orders-api");
  });

  it("keeps routes from several ingresses apart by their declaring resource", () => {
    const routes = toGatewayRoutes([
      ingress("shop", [{ kind: "SERVICE", path: "/api", serviceName: "orders-api" }]),
      ingress("admin", [{ kind: "SERVICE", path: "/admin", serviceName: "admin-api" }]),
    ]);

    expect(routes.map(routeKey)).toEqual(["shop#0", "admin#0"]);
  });

  it("reads a host constraint, leaving an unconstrained route null", () => {
    const routes = toGatewayRoutes([
      ingress("edge", [
        {
          kind: "SERVICE",
          path: "/checkout",
          host: "shop.example.com",
          serviceName: "checkout-api",
        },
        { kind: "SERVICE", path: "/api/orders", serviceName: "orders-api" },
      ]),
    ]);

    expect(routes[0].host).toBe("shop.example.com");
    expect(routes[1].host).toBeNull();
  });

  /**
   * A FABRIC route is permanently exact-path-only, so a prefix flag arriving on one is the control
   * plane and this console disagreeing -- rendering it as a prefix would claim the gateway matches
   * paths it never will.
   */
  it("never treats a fabric route as a prefix route", () => {
    const routes = toGatewayRoutes([
      ingress("edge", [
        {
          kind: "FABRIC",
          path: "/greet",
          prefix: true,
          interfaceName: "com.acme.Greeter",
          majorVersion: 1,
          methodName: "greet",
          paramType: "NONE",
        },
      ]),
    ]);

    expect(routes[0].prefix).toBe(false);
  });

  /**
   * A route kind the control plane accepted but this console has no renderer for is a console that
   * is behind, not a cluster that is wrong -- dropping it beats drawing a row of blanks.
   */
  it("drops a route kind it does not know rather than rendering it broken", () => {
    const routes = toGatewayRoutes([
      ingress("edge", [
        { kind: "GRPC", path: "/rpc" },
        { kind: "SERVICE", path: "/api", serviceName: "orders-api" },
      ]),
    ]);

    expect(routes).toHaveLength(1);
    expect(routes[0].path).toBe("/api");
  });
});

describe("route display", () => {
  it("names each kind's target", () => {
    const [fabric, vessel, service] = toGatewayRoutes([
      ingress("edge", [
        {
          kind: "FABRIC",
          path: "/greet",
          interfaceName: "com.acme.Greeter",
          majorVersion: 2,
          methodName: "greet",
          paramType: "STRING",
        },
        { kind: "VESSEL", path: "/static", deploymentName: "assets", portName: "HTTP_PORT" },
        { kind: "SERVICE", path: "/api", serviceName: "orders-api" },
      ]),
    ]);

    expect(routeTarget(fabric)).toBe("com.acme.Greeter v2 greet()");
    expect(routeTarget(vessel as VesselRoute)).toBe("assets (HTTP_PORT)");
    expect(routeTarget(service)).toBe("orders-api");
  });

  it("shows a prefix route with its trailing wildcard, and a catch-all as bare /*", () => {
    const [prefixed, catchAll] = toGatewayRoutes([
      ingress("edge", [
        { kind: "SERVICE", path: "/api", prefix: true, serviceName: "orders-api" },
        { kind: "SERVICE", path: "/", prefix: true, serviceName: "fallback-api" },
      ]),
    ]);

    expect(routePathDisplay(prefixed)).toBe("/api/*");
    expect(routePathDisplay(catchAll)).toBe("/*");
  });
});
