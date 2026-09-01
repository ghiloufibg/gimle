import { createFileRoute } from "@tanstack/react-router";
import { useEffect, useState } from "react";
import { toast } from "sonner";
import { notifyApiError } from "@/lib/api-error";
import { Pencil, Plus, RefreshCw, Trash2, X } from "lucide-react";

import { PageContainer, PageHeader } from "@/components/page-shell";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
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
import { PermissionRows } from "@/components/rbac/permission-rows";
import {
  RolePicker,
  SubjectPicker,
  type SubjectType,
} from "@/components/rbac/subject-role-pickers";
import { useRolesStore } from "@/stores/useRolesStore";
import { useRoleBindingsStore } from "@/stores/useRoleBindingsStore";
import { useAccountsStore } from "@/stores/useAccountsStore";
import type { Account, Permission } from "@/types";

export const Route = createFileRoute("/access-control")({
  head: () => ({
    meta: [
      { title: "Access Control — Gimlé Console" },
      {
        name: "description",
        content: "Manage cluster RBAC roles, role bindings and accounts.",
      },
      { property: "og:title", content: "Access Control — Gimlé Console" },
      {
        property: "og:description",
        content: "Manage cluster RBAC roles, role bindings and accounts.",
      },
    ],
  }),
  component: AccessControlPage,
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

function AccessControlPage() {
  return (
    <PageContainer>
      <PageHeader
        title="Access Control"
        subtitle="Roles, role bindings and accounts for the cluster control plane."
      />
      <Tabs defaultValue="roles">
        <TabsList className="mb-4">
          <TabsTrigger value="roles">Roles</TabsTrigger>
          <TabsTrigger value="bindings">Role Bindings</TabsTrigger>
          <TabsTrigger value="accounts">Accounts</TabsTrigger>
        </TabsList>
        <TabsContent value="roles">
          <RolesTab />
        </TabsContent>
        <TabsContent value="bindings">
          <RoleBindingsTab />
        </TabsContent>
        <TabsContent value="accounts">
          <AccountsTab />
        </TabsContent>
      </Tabs>
    </PageContainer>
  );
}

/* ------------------------------- Roles ---------------------------------- */

function RolesTab() {
  const { items, loading, loaded, error, load, refresh, save, remove } = useRolesStore();
  const [editing, setEditing] = useState<string | null>(null);
  const [name, setName] = useState("");
  const [permissions, setPermissions] = useState<Permission[]>([]);

  useEffect(() => {
    if (!loaded) load();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  function reset() {
    setEditing(null);
    setName("");
    setPermissions([]);
  }

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    if (!name.trim()) return;
    try {
      await save(name.trim(), permissions);
      toast.success(`Role ${name.trim()} saved`);
      reset();
    } catch (err) {
      notifyApiError(err);
    }
  }

  return (
    <div>
      {error && <ErrorBanner message={error} />}
      <form onSubmit={submit} className="mb-4 grid gap-3 rounded border border-border bg-card p-3">
        <div className="flex items-end justify-between gap-2">
          <div className="grid gap-1">
            <Label className="text-[10px] uppercase tracking-wider text-muted-foreground">
              Role name
            </Label>
            <Input
              className="h-8 w-56 font-mono text-xs"
              value={name}
              disabled={editing !== null}
              onChange={(e) => setName(e.target.value)}
              placeholder="tenant-operator"
            />
          </div>
          <div className="flex items-center gap-2">
            {editing && (
              <Button type="button" variant="ghost" size="sm" onClick={reset}>
                <X className="h-3.5 w-3.5" />
                Cancel
              </Button>
            )}
            <Button type="submit" size="sm" disabled={loading}>
              <Plus className="h-3.5 w-3.5" />
              {editing ? "Save role" : "Create role"}
            </Button>
          </div>
        </div>
        <PermissionRows value={permissions} onChange={setPermissions} />
      </form>

      <div className="mb-2 flex items-center justify-between">
        <span className="text-xs text-muted-foreground">{items.length} roles</span>
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
              <th className="px-2 py-1.5 font-medium">Permissions</th>
              <th className="px-2 py-1.5 font-medium w-20"></th>
            </tr>
          </thead>
          <tbody>
            {items.map((r) => (
              <tr key={r.name} className="border-t border-border hover:bg-muted/30">
                <td className="px-2 py-1.5 font-mono">{r.name}</td>
                <td className="px-2 py-1.5 font-mono tabular-nums">{r.permissions.length}</td>
                <td className="px-2 py-1.5">
                  <div className="flex items-center gap-3">
                    <button
                      onClick={() => {
                        setEditing(r.name);
                        setName(r.name);
                        setPermissions(r.permissions.map((p) => ({ ...p })));
                      }}
                      className="text-muted-foreground hover:text-foreground"
                      aria-label={`Edit role ${r.name}`}
                    >
                      <Pencil className="h-3.5 w-3.5" />
                    </button>
                    <Confirm
                      title={`Delete role ${r.name}?`}
                      description="Bindings referencing this role will dangle until updated."
                      onConfirm={async () => {
                        try {
                          await remove(r.name);
                          toast.success("Role deleted");
                        } catch (err) {
                          notifyApiError(err);
                        }
                      }}
                    />
                  </div>
                </td>
              </tr>
            ))}
            {loading && items.length === 0 && (
              <tr>
                <td colSpan={3} className="px-4 py-6 text-center text-muted-foreground">
                  Loading…
                </td>
              </tr>
            )}
            {!loading && items.length === 0 && (
              <tr>
                <td colSpan={3} className="px-4 py-6 text-center text-muted-foreground">
                  No roles.
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </div>
    </div>
  );
}

/* ---------------------------- Role Bindings ------------------------------ */

function slugify(v: string) {
  return v
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, "-")
    .replace(/^-|-$/g, "");
}

function RoleBindingsTab() {
  const { items, loading, loaded, error, load, refresh, save, remove } = useRoleBindingsStore();
  const roles = useRolesStore((s) => s.items);
  const rolesLoaded = useRolesStore((s) => s.loaded);
  const loadRoles = useRolesStore((s) => s.load);

  const [editing, setEditing] = useState<string | null>(null);
  const [id, setId] = useState("");
  const [subjectType, setSubjectType] = useState<SubjectType>("user");
  const [subjectName, setSubjectName] = useState("");
  const [roleName, setRoleName] = useState("");

  useEffect(() => {
    if (!loaded) load();
    if (!rolesLoaded) loadRoles();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  function reset() {
    setEditing(null);
    setId("");
    setSubjectType("user");
    setSubjectName("");
    setRoleName("");
  }

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    const trimmed = subjectName.trim();
    if (!trimmed) {
      toast.error("Subject name is required");
      return;
    }
    if (!roleName.trim()) {
      toast.error("Role name is required");
      return;
    }
    const subject = `${subjectType}:${trimmed}`;
    const bindingId = (editing ?? id.trim()) || slugify(`${trimmed}-${roleName}`);
    try {
      await save(bindingId, { subject, roleName: roleName.trim() });
      toast.success(`Binding ${bindingId} saved`);
      reset();
    } catch (err) {
      notifyApiError(err);
    }
  }

  return (
    <div>
      {error && <ErrorBanner message={error} />}
      <form
        onSubmit={submit}
        className="mb-4 flex flex-wrap items-end gap-3 rounded border border-border bg-card p-3"
      >
        <div className="grid gap-1">
          <Label className="text-[10px] uppercase tracking-wider text-muted-foreground">
            Binding ID (optional)
          </Label>
          <Input
            className="h-8 w-52 font-mono text-xs"
            value={editing ?? id}
            disabled={editing !== null}
            onChange={(e) => setId(e.target.value)}
            placeholder="auto from subject + role"
          />
        </div>
        <SubjectPicker
          type={subjectType}
          name={subjectName}
          onTypeChange={setSubjectType}
          onNameChange={setSubjectName}
        />
        <RolePicker value={roleName} roleNames={roles.map((r) => r.name)} onChange={setRoleName} />
        <div className="flex items-center gap-2 pb-0.5">
          {editing && (
            <Button type="button" variant="ghost" size="sm" onClick={reset}>
              <X className="h-3.5 w-3.5" />
              Cancel
            </Button>
          )}
          <Button type="submit" size="sm" disabled={loading}>
            <Plus className="h-3.5 w-3.5" />
            {editing ? "Save binding" : "Create binding"}
          </Button>
        </div>
      </form>

      <div className="mb-2 flex items-center justify-between">
        <span className="text-xs text-muted-foreground">{items.length} bindings</span>
        <Button variant="outline" size="sm" onClick={() => refresh()} disabled={loading}>
          <RefreshCw className="h-3.5 w-3.5" />
          Refresh
        </Button>
      </div>

      <div className="overflow-x-auto rounded border border-border bg-card">
        <table className="w-full text-xs">
          <thead className="bg-muted/50 text-muted-foreground">
            <tr className="text-left">
              <th className="px-2 py-1.5 font-medium">ID</th>
              <th className="px-2 py-1.5 font-medium">Subject</th>
              <th className="px-2 py-1.5 font-medium">Role</th>
              <th className="px-2 py-1.5 font-medium w-20"></th>
            </tr>
          </thead>
          <tbody>
            {items.map((b) => (
              <tr key={b.id} className="border-t border-border hover:bg-muted/30">
                <td className="px-2 py-1.5 font-mono">{b.id}</td>
                <td className="px-2 py-1.5 font-mono">{b.subject}</td>
                <td className="px-2 py-1.5 font-mono">{b.roleName}</td>
                <td className="px-2 py-1.5">
                  <div className="flex items-center gap-3">
                    <button
                      onClick={() => {
                        const [kind, ...rest] = b.subject.split(":");
                        setEditing(b.id);
                        setSubjectType(kind === "group" ? "group" : "user");
                        setSubjectName(rest.join(":"));
                        setRoleName(b.roleName);
                      }}
                      className="text-muted-foreground hover:text-foreground"
                      aria-label={`Edit binding ${b.id}`}
                    >
                      <Pencil className="h-3.5 w-3.5" />
                    </button>
                    <Confirm
                      title={`Delete binding ${b.id}?`}
                      description="The subject immediately loses the permissions granted by this role."
                      onConfirm={async () => {
                        try {
                          await remove(b.id);
                          toast.success("Binding deleted");
                        } catch (err) {
                          notifyApiError(err);
                        }
                      }}
                    />
                  </div>
                </td>
              </tr>
            ))}
            {loading && items.length === 0 && (
              <tr>
                <td colSpan={4} className="px-4 py-6 text-center text-muted-foreground">
                  Loading…
                </td>
              </tr>
            )}
            {!loading && items.length === 0 && (
              <tr>
                <td colSpan={4} className="px-4 py-6 text-center text-muted-foreground">
                  No role bindings.
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </div>
    </div>
  );
}

/* ------------------------------ Accounts --------------------------------- */

function AccountsTab() {
  const { items, loading, loaded, error, load, refresh, savePassword, remove } = useAccountsStore();
  const [editing, setEditing] = useState<string | null>(null);
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [groups, setGroups] = useState("");

  useEffect(() => {
    if (!loaded) load();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  function reset() {
    setEditing(null);
    setUsername("");
    setPassword("");
    setGroups("");
  }

  function startEdit(a: Account) {
    setEditing(a.username);
    setPassword("");
    setGroups(a.groups.join(", "));
  }

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    const user = (editing ?? username).trim();
    if (!user || !password) {
      toast.error("Username and password are required");
      return;
    }
    const parsedGroups = groups
      .split(",")
      .map((g) => g.trim())
      .filter((g) => g.length > 0);
    try {
      await savePassword(user, password, parsedGroups);
      toast.success(`Password set for ${user}`);
      reset();
    } catch (err) {
      notifyApiError(err);
    }
  }

  return (
    <div>
      {error && <ErrorBanner message={error} />}
      <form
        onSubmit={submit}
        className="mb-4 flex flex-wrap items-end gap-3 rounded border border-border bg-card p-3"
      >
        <div className="grid gap-1">
          <Label className="text-[10px] uppercase tracking-wider text-muted-foreground">
            Username
          </Label>
          <Input
            className="h-8 w-52 font-mono text-xs"
            value={editing ?? username}
            disabled={editing !== null}
            onChange={(e) => setUsername(e.target.value)}
            placeholder="operator"
          />
        </div>
        <div className="grid gap-1">
          <Label className="text-[10px] uppercase tracking-wider text-muted-foreground">
            Password
          </Label>
          <Input
            type="password"
            className="h-8 w-52 font-mono text-xs"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            placeholder="••••••••"
          />
        </div>
        <div className="grid gap-1">
          <Label className="text-[10px] uppercase tracking-wider text-muted-foreground">
            Groups
          </Label>
          <Input
            className="h-8 w-52 font-mono text-xs"
            value={groups}
            onChange={(e) => setGroups(e.target.value)}
            placeholder="ops, auditors"
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
            Set / Reset Password
          </Button>
        </div>
      </form>

      <div className="mb-2 flex items-center justify-between">
        <span className="text-xs text-muted-foreground">{items.length} accounts</span>
        <Button variant="outline" size="sm" onClick={() => refresh()} disabled={loading}>
          <RefreshCw className="h-3.5 w-3.5" />
          Refresh
        </Button>
      </div>

      <div className="overflow-x-auto rounded border border-border bg-card">
        <table className="w-full text-xs">
          <thead className="bg-muted/50 text-muted-foreground">
            <tr className="text-left">
              <th className="px-2 py-1.5 font-medium">Username</th>
              <th className="px-2 py-1.5 font-medium">Groups</th>
              <th className="px-2 py-1.5 font-medium w-40"></th>
            </tr>
          </thead>
          <tbody>
            {items.map((a) => (
              <tr key={a.username} className="border-t border-border hover:bg-muted/30">
                <td className="px-2 py-1.5 font-mono">{a.username}</td>
                <td className="px-2 py-1.5">
                  {a.groups.length === 0 ? (
                    <span className="text-muted-foreground">—</span>
                  ) : (
                    <div className="flex flex-wrap gap-1">
                      {a.groups.map((g) => (
                        <span
                          key={g}
                          className="rounded bg-muted px-1.5 py-0.5 font-mono text-[10px]"
                        >
                          {g}
                        </span>
                      ))}
                    </div>
                  )}
                </td>
                <td className="px-2 py-1.5">
                  <div className="flex items-center gap-3">
                    <button
                      onClick={() => startEdit(a)}
                      className="text-[10px] uppercase tracking-wider text-muted-foreground hover:text-foreground"
                    >
                      Set / reset password
                    </button>
                    <Confirm
                      title={`Delete account ${a.username}?`}
                      description="The account can no longer authenticate against the control plane."
                      onConfirm={async () => {
                        try {
                          await remove(a.username);
                          toast.success("Account deleted");
                        } catch (err) {
                          notifyApiError(err);
                        }
                      }}
                    />
                  </div>
                </td>
              </tr>
            ))}
            {loading && items.length === 0 && (
              <tr>
                <td colSpan={3} className="px-4 py-6 text-center text-muted-foreground">
                  Loading…
                </td>
              </tr>
            )}
            {!loading && items.length === 0 && (
              <tr>
                <td colSpan={3} className="px-4 py-6 text-center text-muted-foreground">
                  No accounts.
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </div>
    </div>
  );
}
