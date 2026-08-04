package com.gimle.core.authz;

/**
 * Every kind of resource an {@code ApiServer} handler guards -- one entry per resource the API
 * surface actually exposes today, not a speculative superset. {@code ROLE}/{@code ROLE_BINDING}/
 * {@code ACCOUNT} guard the RBAC objects themselves -- who can grant access is itself an
 * access-controlled action, resolved through this same enum rather than a separate special case.
 */
public enum ResourceKind {
  DEPLOYMENT,
  NODE,
  TENANT,
  CONFIG,
  LOGS,
  CERTIFICATE_REQUEST,
  BOOTSTRAP_TOKEN,
  ROLE,
  ROLE_BINDING,
  ACCOUNT
}
