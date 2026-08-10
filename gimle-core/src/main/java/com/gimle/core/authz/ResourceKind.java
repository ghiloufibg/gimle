package com.gimle.core.authz;

/**
 * Every kind of resource an {@code ApiServer} handler guards -- one entry per resource the API
 * surface actually exposes today, not a speculative superset. {@code ROLE}/{@code ROLE_BINDING}/
 * {@code ACCOUNT} guard the RBAC objects themselves -- who can grant access is itself an
 * access-controlled action, resolved through this same enum rather than a separate special case;
 * {@code AUDIT} is the identical situation applied to reading the audit trail itself. {@code
 * SECRET} guards a tenant's {@code ConfigEntry}s written with {@code encrypted=true}, distinct from
 * {@code CONFIG} (plaintext entries) so a role can be granted one without the other -- {@code
 * BuiltinRoles.CLUSTER_ADMIN} picks it up automatically since it iterates {@link #values()} at
 * class-load time.
 */
public enum ResourceKind {
  DEPLOYMENT,
  NODE,
  TENANT,
  CONFIG,
  SECRET,
  LOGS,
  CERTIFICATE_REQUEST,
  BOOTSTRAP_TOKEN,
  ROLE,
  ROLE_BINDING,
  ACCOUNT,
  AUDIT
}
