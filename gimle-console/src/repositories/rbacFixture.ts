import type { Account, Role, RoleBinding } from "@/types";

/** In-memory RBAC fixture backing the Mock* RBAC repositories. */
export const roles: Role[] = [
  {
    name: "cluster-admin",
    permissions: [
      { resource: "DEPLOYMENT", verb: "WRITE" },
      { resource: "NODE", verb: "READ" },
      { resource: "TENANT", verb: "WRITE" },
      { resource: "ROLE", verb: "WRITE" },
      { resource: "ROLE_BINDING", verb: "WRITE" },
      { resource: "ACCOUNT", verb: "WRITE" },
      { resource: "AUDIT", verb: "READ" },
    ],
  },
  {
    name: "tenant-operator",
    permissions: [
      { resource: "DEPLOYMENT", verb: "WRITE", tenantScope: "acme" },
      { resource: "CONFIG", verb: "WRITE", tenantScope: "acme" },
      { resource: "LOGS", verb: "READ", tenantScope: "acme" },
    ],
  },
  {
    name: "readonly",
    permissions: [
      { resource: "DEPLOYMENT", verb: "READ" },
      { resource: "NODE", verb: "READ" },
      { resource: "LOGS", verb: "READ" },
    ],
  },
  {
    name: "cert-approver",
    permissions: [{ resource: "CERTIFICATE_REQUEST", verb: "APPROVE" }],
  },
];

export const roleBindings: RoleBinding[] = [
  { id: "admin-cluster-admin", subject: "user:admin", roleName: "cluster-admin" },
  { id: "ops-tenant-operator", subject: "group:ops", roleName: "tenant-operator" },
  { id: "audit-readonly", subject: "group:auditors", roleName: "readonly" },
];

export const accounts: Account[] = [
  { username: "admin", groups: [] },
  { username: "operator", groups: ["ops"] },
  { username: "auditor", groups: ["auditors"] },
];

export function upsertRole(role: Role) {
  const i = roles.findIndex((r) => r.name === role.name);
  if (i >= 0) roles[i] = role;
  else roles.push(role);
}

export function removeRole(name: string) {
  const i = roles.findIndex((r) => r.name === name);
  if (i >= 0) roles.splice(i, 1);
}

export function upsertRoleBinding(binding: RoleBinding) {
  const i = roleBindings.findIndex((b) => b.id === binding.id);
  if (i >= 0) roleBindings[i] = binding;
  else roleBindings.push(binding);
}

export function removeRoleBinding(id: string) {
  const i = roleBindings.findIndex((b) => b.id === id);
  if (i >= 0) roleBindings.splice(i, 1);
}

export function upsertAccount(username: string, groups?: string[]) {
  const i = accounts.findIndex((a) => a.username === username);
  if (i >= 0) {
    if (groups !== undefined) accounts[i] = { ...accounts[i], groups };
  } else {
    accounts.push({ username, groups: groups ?? [] });
  }
}

export function removeAccount(username: string) {
  const i = accounts.findIndex((a) => a.username === username);
  if (i >= 0) accounts.splice(i, 1);
}
