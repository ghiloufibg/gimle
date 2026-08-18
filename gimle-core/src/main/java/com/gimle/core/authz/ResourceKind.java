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
  // Covers both Job and CronJob routes under one kind -- a CronJob is "the authority to eventually
  // create Jobs," so splitting RBAC finer than that buys nothing yet. Tenant-scopable, same as
  // DEPLOYMENT.
  JOB,
  // Deliberately its own kind, not folded into DEPLOYMENT: "may deploy something that runs on
  // every node in the cluster" is a meaningfully more consequential grant than an ordinary scoped
  // deployment, worth letting operators withhold independently.
  DAEMONSET,
  // Deliberately its own kind too, same reasoning as DAEMONSET: a workload that owns persistent
  // local-disk state with real data-loss consequences on mismanagement deserves independently
  // grantable RBAC, not folded into DEPLOYMENT.
  STATEFULSET,
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
  AUDIT,
  // Guards the Andvari artifact registry: pushing or deleting a module jar is a supply-chain-
  // adjacent grant, meaningfully more consequential than an ordinary deployment submission, so it
  // gets its own independently withholdable kind rather than being folded into DEPLOYMENT -- the
  // same reasoning DAEMONSET/STATEFULSET already established.
  ARTIFACT,
  // Guards declaring/editing a Service (the ClusterIP analogue named in the platform's own
  // network-model design): a stable address a Deployment/DaemonSet becomes reachable at is a
  // meaningfully different grant than submitting the workload itself, worth withholding
  // independently -- same reasoning as ARTIFACT/DAEMONSET/STATEFULSET above.
  SERVICE,
  // Guards declaring/editing a NetworkPolicy. Deliberately its own kind rather than folded into
  // SERVICE: a NetworkPolicy restricts *other* tenants' access to what a Service exposes, so
  // granting it is a materially different, more consequential authority than merely being able to
  // declare a Service in the first place.
  NETWORK_POLICY
}
