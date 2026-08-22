import { createFileRoute } from "@tanstack/react-router";
import { Fragment, useEffect, useState } from "react";
import { toast } from "sonner";
import { ChevronDown, ChevronRight, Pencil, Plus, RefreshCw, Trash2, X } from "lucide-react";

import { PageContainer, PageHeader } from "@/components/page-shell";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
  AlertDialogTrigger,
} from "@/components/ui/alert-dialog";
import { useServicesStore } from "@/stores/useServicesStore";
import { useNetworkPoliciesStore } from "@/stores/useNetworkPoliciesStore";
import { useTenantsStore } from "@/stores/useTenantsStore";
import type { NetworkPolicy, Service, ServiceEndpoints } from "@/types";

export const Route = createFileRoute("/networking")({
  head: () => ({
    meta: [
      { title: "Networking — Gimlé Console" },
      {
        name: "description",
        content: "Services (ClusterIP analogue) and NetworkPolicy cross-tenant access rules.",
      },
      { property: "og:title", content: "Networking — Gimlé Console" },
      {
        property: "og:description",
        content: "Services (ClusterIP analogue) and NetworkPolicy cross-tenant access rules.",
      },
    ],
  }),
  component: NetworkingPage,
});

function ErrorBanner({ message }: { message: string }) {
  return (
    <div className="mb-3 rounded border border-status-bad/40 bg-status-bad-bg/40 px-3 py-2 text-xs text-status-bad">
      {message}
    </div>
  );
}

function Confirm({
  title,
  description,
  onConfirm,
}: {
  title: string;
  description: string;
  onConfirm: () => void;
}) {
  return (
    <AlertDialog>
      <AlertDialogTrigger asChild>
        <button className="text-muted-foreground hover:text-status-bad" aria-label={title}>
          <Trash2 className="h-3.5 w-3.5" />
        </button>
      </AlertDialogTrigger>
      <AlertDialogContent>
        <AlertDialogHeader>
          <AlertDialogTitle>{title}</AlertDialogTitle>
          <AlertDialogDescription>{description}</AlertDialogDescription>
        </AlertDialogHeader>
        <AlertDialogFooter>
          <AlertDialogCancel>Cancel</AlertDialogCancel>
          <AlertDialogAction
            onClick={onConfirm}
            className="bg-destructive text-destructive-foreground hover:bg-destructive/90"
          >
            Delete
          </AlertDialogAction>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
  );
}

/** Tenant dropdown, falling back to free text while the tenant list hasn't loaded yet -- the same
 * fallback `RolePicker` (access-control.tsx) uses for its own picker. */
function TenantPicker({
  value,
  tenantIds,
  onChange,
  placeholder,
  allowEmpty,
}: {
  value: string;
  tenantIds: string[];
  onChange: (v: string) => void;
  placeholder: string;
  allowEmpty?: boolean;
}) {
  if (tenantIds.length === 0) {
    return (
      <Input
        className="h-8 w-40 font-mono text-xs"
        placeholder={placeholder}
        value={value}
        onChange={(e) => onChange(e.target.value)}
      />
    );
  }
  return (
    <Select value={value} onValueChange={(v) => onChange(v === "__none__" ? "" : v)}>
      <SelectTrigger className="h-8 w-40 font-mono text-xs">
        <SelectValue placeholder={placeholder} />
      </SelectTrigger>
      <SelectContent>
        {allowEmpty && (
          <SelectItem value="__none__" className="text-xs">
            (none)
          </SelectItem>
        )}
        {tenantIds.map((id) => (
          <SelectItem key={id} value={id} className="font-mono text-xs">
            {id}
          </SelectItem>
        ))}
      </SelectContent>
    </Select>
  );
}

function parseCsv(value: string): string[] {
  return Array.from(
    new Set(
      value
        .split(",")
        .map((v) => v.trim())
        .filter(Boolean),
    ),
  );
}

function NetworkingPage() {
  return (
    <PageContainer>
      <PageHeader
        title="Networking"
        subtitle="Services (the ClusterIP analogue) and the NetworkPolicies that restrict cross-tenant access to them."
      />
      <Tabs defaultValue="services">
        <TabsList className="mb-4">
          <TabsTrigger value="services">Services</TabsTrigger>
          <TabsTrigger value="networkpolicies">NetworkPolicies</TabsTrigger>
        </TabsList>
        <TabsContent value="services">
          <ServicesTab />
        </TabsContent>
        <TabsContent value="networkpolicies">
          <NetworkPoliciesTab />
        </TabsContent>
      </Tabs>
    </PageContainer>
  );
}

/* -------------------------------- Services -------------------------------- */

const emptyServiceForm = {
  tenantId: "",
  deploymentNames: "",
  port: "",
  targetPort: "",
};

function ServicesTab() {
  const { items, loading, loaded, error, load, refresh, save, remove, fetchEndpoints } =
    useServicesStore();
  const tenants = useTenantsStore((s) => s.items);
  const loadTenants = useTenantsStore((s) => s.loadFirstPage);

  const [editing, setEditing] = useState<string | null>(null);
  const [name, setName] = useState("");
  const [form, setForm] = useState(emptyServiceForm);
  const [expanded, setExpanded] = useState<Set<string>>(new Set());
  const [endpoints, setEndpoints] = useState<Record<string, ServiceEndpoints | "loading">>({});

  useEffect(() => {
    if (!loaded) load();
    if (tenants.length === 0) loadTenants();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  function reset() {
    setEditing(null);
    setName("");
    setForm(emptyServiceForm);
  }

  async function toggleExpanded(svc: Service) {
    const next = new Set(expanded);
    if (next.has(svc.name)) {
      next.delete(svc.name);
      setExpanded(next);
      return;
    }
    next.add(svc.name);
    setExpanded(next);
    setEndpoints((prev) => ({ ...prev, [svc.name]: "loading" }));
    try {
      const result = await fetchEndpoints(svc.name);
      setEndpoints((prev) => ({ ...prev, [svc.name]: result }));
    } catch (err) {
      toast.error((err as Error).message);
      setExpanded((prevExpanded) => {
        const cleared = new Set(prevExpanded);
        cleared.delete(svc.name);
        return cleared;
      });
    }
  }

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    const trimmed = name.trim();
    if (!trimmed) {
      toast.error("Service name is required");
      return;
    }
    const deploymentNames = parseCsv(form.deploymentNames);
    if (deploymentNames.length === 0) {
      toast.error("At least one deployment name is required");
      return;
    }
    const port = Number(form.port);
    if (!Number.isInteger(port) || port < 1 || port > 65535) {
      toast.error("Port must be an integer in [1, 65535]");
      return;
    }
    const targetPort = form.targetPort.trim() ? Number(form.targetPort) : port;
    if (!Number.isInteger(targetPort) || targetPort < 1 || targetPort > 65535) {
      toast.error("Target port must be an integer in [1, 65535]");
      return;
    }
    const spec: Service = {
      name: trimmed,
      ...(form.tenantId ? { tenantId: form.tenantId } : {}),
      deploymentNames,
      port,
      targetPort,
    };
    try {
      await save(spec);
      toast.success(`Service ${trimmed} saved`);
      reset();
    } catch (err) {
      toast.error((err as Error).message);
    }
  }

  return (
    <div>
      {error && <ErrorBanner message={error} />}
      <form onSubmit={submit} className="mb-4 grid gap-3 rounded border border-border bg-card p-3">
        <div className="flex flex-wrap items-end gap-3">
          <div className="grid gap-1">
            <Label className="text-[10px] uppercase tracking-wider text-muted-foreground">
              Name
            </Label>
            <Input
              className="h-8 w-44 font-mono text-xs"
              value={name}
              disabled={editing !== null}
              onChange={(e) => setName(e.target.value)}
              placeholder="orders-web"
            />
          </div>
          <div className="grid gap-1">
            <Label className="text-[10px] uppercase tracking-wider text-muted-foreground">
              Tenant
            </Label>
            <TenantPicker
              value={form.tenantId}
              tenantIds={tenants.map((t) => t.id)}
              onChange={(v) => setForm({ ...form, tenantId: v })}
              placeholder="(none)"
              allowEmpty
            />
          </div>
          <div className="grid gap-1 flex-1 min-w-[220px]">
            <Label className="text-[10px] uppercase tracking-wider text-muted-foreground">
              Deployment names (comma-separated)
            </Label>
            <Input
              className="h-8 font-mono text-xs"
              value={form.deploymentNames}
              onChange={(e) => setForm({ ...form, deploymentNames: e.target.value })}
              placeholder="orders-service, orders-service-canary"
            />
          </div>
          <div className="grid gap-1">
            <Label className="text-[10px] uppercase tracking-wider text-muted-foreground">
              Port
            </Label>
            <Input
              className="h-8 w-20 font-mono text-xs"
              value={form.port}
              onChange={(e) => setForm({ ...form, port: e.target.value })}
              placeholder="8080"
              inputMode="numeric"
            />
          </div>
          <div className="grid gap-1">
            <Label className="text-[10px] uppercase tracking-wider text-muted-foreground">
              Target port
            </Label>
            <Input
              className="h-8 w-20 font-mono text-xs"
              value={form.targetPort}
              onChange={(e) => setForm({ ...form, targetPort: e.target.value })}
              placeholder="= port"
              inputMode="numeric"
            />
          </div>
          <div className="flex items-center gap-2 pb-0.5">
            {editing && (
              <Button type="button" variant="ghost" size="sm" onClick={reset}>
                <X className="h-3.5 w-3.5" />
                Cancel
              </Button>
            )}
            <Button type="submit" size="sm" disabled={loading}>
              <Plus className="h-3.5 w-3.5" />
              {editing ? "Save service" : "Create service"}
            </Button>
          </div>
        </div>
      </form>

      <div className="mb-2 flex items-center justify-between">
        <span className="text-xs text-muted-foreground">{items.length} services</span>
        <Button variant="outline" size="sm" onClick={() => refresh()} disabled={loading}>
          <RefreshCw className="h-3.5 w-3.5" />
          Refresh
        </Button>
      </div>

      <div className="overflow-x-auto rounded border border-border bg-card">
        <table className="w-full text-xs">
          <thead className="bg-muted/50 text-muted-foreground">
            <tr className="text-left">
              <th className="px-2 py-1.5 font-medium w-6"></th>
              <th className="px-2 py-1.5 font-medium">Name</th>
              <th className="px-2 py-1.5 font-medium">Tenant</th>
              <th className="px-2 py-1.5 font-medium">Deployments</th>
              <th className="px-2 py-1.5 font-medium">Port → Target</th>
              <th className="px-2 py-1.5 font-medium w-20"></th>
            </tr>
          </thead>
          <tbody>
            {items.map((s) => {
              const isOpen = expanded.has(s.name);
              const ep = endpoints[s.name];
              return (
                <Fragment key={s.name}>
                  <tr className="border-t border-border hover:bg-muted/30">
                    <td className="px-2 py-1.5">
                      <button
                        onClick={() => toggleExpanded(s)}
                        className="text-muted-foreground hover:text-foreground"
                        aria-label={isOpen ? "Hide endpoints" : "Show endpoints"}
                      >
                        {isOpen ? (
                          <ChevronDown className="h-3.5 w-3.5" />
                        ) : (
                          <ChevronRight className="h-3.5 w-3.5" />
                        )}
                      </button>
                    </td>
                    <td className="px-2 py-1.5 font-mono">{s.name}</td>
                    <td className="px-2 py-1.5 font-mono">{s.tenantId ?? "—"}</td>
                    <td className="px-2 py-1.5 font-mono">{s.deploymentNames.join(", ")}</td>
                    <td className="px-2 py-1.5 font-mono tabular-nums">
                      {s.port} → {s.targetPort}
                    </td>
                    <td className="px-2 py-1.5">
                      <div className="flex items-center gap-3">
                        <button
                          onClick={() => {
                            setEditing(s.name);
                            setName(s.name);
                            setForm({
                              tenantId: s.tenantId ?? "",
                              deploymentNames: s.deploymentNames.join(", "),
                              port: String(s.port),
                              targetPort: String(s.targetPort),
                            });
                          }}
                          className="text-muted-foreground hover:text-foreground"
                          aria-label={`Edit service ${s.name}`}
                        >
                          <Pencil className="h-3.5 w-3.5" />
                        </button>
                        <Confirm
                          title={`Delete service ${s.name}?`}
                          description="Callers dialing this name lose their stable address immediately."
                          onConfirm={async () => {
                            try {
                              await remove(s.name);
                              toast.success("Service deleted");
                            } catch (err) {
                              toast.error((err as Error).message);
                            }
                          }}
                        />
                      </div>
                    </td>
                  </tr>
                  {isOpen && (
                    <tr className="border-t border-border bg-muted/20">
                      <td></td>
                      <td colSpan={5} className="px-2 py-1.5">
                        {ep === "loading" || ep === undefined ? (
                          <span className="text-muted-foreground">Loading endpoints…</span>
                        ) : ep.endpoints.length === 0 ? (
                          <span className="text-muted-foreground">
                            No live backing instances yet.
                          </span>
                        ) : (
                          <span className="font-mono">
                            {ep.endpoints.map((e) => `${e.host}:${e.port}`).join(", ")}
                          </span>
                        )}
                      </td>
                    </tr>
                  )}
                </Fragment>
              );
            })}
            {loading && items.length === 0 && (
              <tr>
                <td colSpan={6} className="px-4 py-6 text-center text-muted-foreground">
                  Loading…
                </td>
              </tr>
            )}
            {!loading && items.length === 0 && (
              <tr>
                <td colSpan={6} className="px-4 py-6 text-center text-muted-foreground">
                  No services.
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </div>
    </div>
  );
}

/* ---------------------------- NetworkPolicies ------------------------------ */

const emptyPolicyForm = {
  tenantId: "",
  deploymentNames: "",
  allowedCallerTenantIds: "",
};

function NetworkPoliciesTab() {
  const { items, loading, loaded, error, load, refresh, save, remove } = useNetworkPoliciesStore();
  const tenants = useTenantsStore((s) => s.items);
  const loadTenants = useTenantsStore((s) => s.loadFirstPage);

  const [editing, setEditing] = useState<string | null>(null);
  const [name, setName] = useState("");
  const [form, setForm] = useState(emptyPolicyForm);

  useEffect(() => {
    if (!loaded) load();
    if (tenants.length === 0) loadTenants();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  function reset() {
    setEditing(null);
    setName("");
    setForm(emptyPolicyForm);
  }

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    const trimmed = name.trim();
    if (!trimmed) {
      toast.error("NetworkPolicy name is required");
      return;
    }
    if (!form.tenantId) {
      toast.error("Tenant is required");
      return;
    }
    const spec: NetworkPolicy = {
      name: trimmed,
      tenantId: form.tenantId,
      deploymentNames: parseCsv(form.deploymentNames),
      allowedCallerTenantIds: parseCsv(form.allowedCallerTenantIds),
    };
    try {
      await save(spec);
      toast.success(`NetworkPolicy ${trimmed} saved`);
      reset();
    } catch (err) {
      toast.error((err as Error).message);
    }
  }

  return (
    <div>
      {error && <ErrorBanner message={error} />}
      <form onSubmit={submit} className="mb-4 grid gap-3 rounded border border-border bg-card p-3">
        <div className="flex flex-wrap items-end gap-3">
          <div className="grid gap-1">
            <Label className="text-[10px] uppercase tracking-wider text-muted-foreground">
              Name
            </Label>
            <Input
              className="h-8 w-44 font-mono text-xs"
              value={name}
              disabled={editing !== null}
              onChange={(e) => setName(e.target.value)}
              placeholder="acme-billing-policy"
            />
          </div>
          <div className="grid gap-1">
            <Label className="text-[10px] uppercase tracking-wider text-muted-foreground">
              Tenant
            </Label>
            <TenantPicker
              value={form.tenantId}
              tenantIds={tenants.map((t) => t.id)}
              onChange={(v) => setForm({ ...form, tenantId: v })}
              placeholder="Pick tenant"
            />
          </div>
          <div className="grid gap-1 flex-1 min-w-[200px]">
            <Label className="text-[10px] uppercase tracking-wider text-muted-foreground">
              Deployment names (empty = whole tenant)
            </Label>
            <Input
              className="h-8 font-mono text-xs"
              value={form.deploymentNames}
              onChange={(e) => setForm({ ...form, deploymentNames: e.target.value })}
              placeholder="billing-primary, billing-canary"
            />
          </div>
          <div className="grid gap-1 flex-1 min-w-[200px]">
            <Label className="text-[10px] uppercase tracking-wider text-muted-foreground">
              Allowed caller tenants (comma-separated)
            </Label>
            <Input
              className="h-8 font-mono text-xs"
              value={form.allowedCallerTenantIds}
              onChange={(e) => setForm({ ...form, allowedCallerTenantIds: e.target.value })}
              placeholder="partner"
            />
          </div>
          <div className="flex items-center gap-2 pb-0.5">
            {editing && (
              <Button type="button" variant="ghost" size="sm" onClick={reset}>
                <X className="h-3.5 w-3.5" />
                Cancel
              </Button>
            )}
            <Button type="submit" size="sm" disabled={loading}>
              <Plus className="h-3.5 w-3.5" />
              {editing ? "Save policy" : "Create policy"}
            </Button>
          </div>
        </div>
      </form>

      <div className="mb-2 flex items-center justify-between">
        <span className="text-xs text-muted-foreground">{items.length} network policies</span>
        <Button variant="outline" size="sm" onClick={() => refresh()} disabled={loading}>
          <RefreshCw className="h-3.5 w-3.5" />
          Refresh
        </Button>
      </div>

      <div className="overflow-x-auto rounded border border-border bg-card">
        <table className="w-full text-xs">
          <thead className="bg-muted/50 text-muted-foreground">
            <tr className="text-left">
              <th className="px-2 py-1.5 font-medium">Name</th>
              <th className="px-2 py-1.5 font-medium">Tenant</th>
              <th className="px-2 py-1.5 font-medium">Scope</th>
              <th className="px-2 py-1.5 font-medium">Allowed caller tenants</th>
              <th className="px-2 py-1.5 font-medium w-20"></th>
            </tr>
          </thead>
          <tbody>
            {items.map((p) => (
              <tr key={p.name} className="border-t border-border hover:bg-muted/30">
                <td className="px-2 py-1.5 font-mono">{p.name}</td>
                <td className="px-2 py-1.5 font-mono">{p.tenantId}</td>
                <td className="px-2 py-1.5 font-mono">
                  {p.deploymentNames.length === 0 ? "whole tenant" : p.deploymentNames.join(", ")}
                </td>
                <td className="px-2 py-1.5 font-mono">
                  {p.allowedCallerTenantIds.length === 0
                    ? "none (deny all cross-tenant)"
                    : p.allowedCallerTenantIds.join(", ")}
                </td>
                <td className="px-2 py-1.5">
                  <div className="flex items-center gap-3">
                    <button
                      onClick={() => {
                        setEditing(p.name);
                        setName(p.name);
                        setForm({
                          tenantId: p.tenantId,
                          deploymentNames: p.deploymentNames.join(", "),
                          allowedCallerTenantIds: p.allowedCallerTenantIds.join(", "),
                        });
                      }}
                      className="text-muted-foreground hover:text-foreground"
                      aria-label={`Edit network policy ${p.name}`}
                    >
                      <Pencil className="h-3.5 w-3.5" />
                    </button>
                    <Confirm
                      title={`Delete network policy ${p.name}?`}
                      description="Every other tenant currently allowed by this policy loses cross-tenant access immediately."
                      onConfirm={async () => {
                        try {
                          await remove(p.name);
                          toast.success("Network policy deleted");
                        } catch (err) {
                          toast.error((err as Error).message);
                        }
                      }}
                    />
                  </div>
                </td>
              </tr>
            ))}
            {loading && items.length === 0 && (
              <tr>
                <td colSpan={5} className="px-4 py-6 text-center text-muted-foreground">
                  Loading…
                </td>
              </tr>
            )}
            {!loading && items.length === 0 && (
              <tr>
                <td colSpan={5} className="px-4 py-6 text-center text-muted-foreground">
                  No network policies.
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </div>
    </div>
  );
}
