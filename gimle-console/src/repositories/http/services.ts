import type { Service, ServiceEndpoints } from "@/types";
import type { ServicesRepository } from "@/repositories/services";
import { requestJson, requestOk, tenantQuery } from "./apiClient";

/**
 * `GET`/`POST`/`DELETE /services*` -- flat array response, no pagination, matching
 * `HttpRolesRepository`'s own convention for this shape of resource. `save` always POSTs to the
 * bare `/services` collection, never `PUT /services/{name}`: a {@code ServiceSpec} names itself in
 * the request body, the same routing `ApiServer#handlePostService` documents for its own sibling
 * resource kind.
 */
export class HttpServicesRepository implements ServicesRepository {
  async fetchAll(): Promise<Service[]> {
    return requestJson<Service[]>("GET", "/services");
  }

  // No known tenantId is available on a cache miss (the whole point of fetchOne is that the item
  // isn't in the already-loaded list) and no route currently threads one in from its own URL, so
  // this stays scoped to the untenanted namespace -- a tenanted service reached this way still
  // 404s.
  async fetchOne(name: string): Promise<Service> {
    return requestJson<Service>("GET", `/services/${encodeURIComponent(name)}`);
  }

  async fetchEndpoints(name: string, tenantId?: string | null): Promise<ServiceEndpoints> {
    return requestJson<ServiceEndpoints>(
      "GET",
      `/services/${encodeURIComponent(name)}/endpoints${tenantQuery(tenantId)}`,
    );
  }

  async save(spec: Service): Promise<void> {
    await requestOk("POST", "/services", spec);
  }

  async remove(name: string, tenantId?: string | null): Promise<void> {
    await requestOk("DELETE", `/services/${encodeURIComponent(name)}${tenantQuery(tenantId)}`);
  }
}
