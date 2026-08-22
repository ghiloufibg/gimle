import type { Service, ServiceEndpoints } from "@/types";
import type { ServicesRepository } from "@/repositories/services";
import { requestJson, requestOk } from "./apiClient";

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

  async fetchOne(name: string): Promise<Service> {
    return requestJson<Service>("GET", `/services/${encodeURIComponent(name)}`);
  }

  async fetchEndpoints(name: string): Promise<ServiceEndpoints> {
    return requestJson<ServiceEndpoints>("GET", `/services/${encodeURIComponent(name)}/endpoints`);
  }

  async save(spec: Service): Promise<void> {
    await requestOk("POST", "/services", spec);
  }

  async remove(name: string): Promise<void> {
    await requestOk("DELETE", `/services/${encodeURIComponent(name)}`);
  }
}
