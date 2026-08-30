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
  NETWORK_POLICY,
  // Guards a tenant's named, multi-key ConfigMap objects -- distinct from CONFIG (which guards the
  // same tenant's loose flat keys) so a role can be granted "read flat config keys" without also
  // getting "read named ConfigMaps," the same split CONFIG/SECRET already establishes for
  // encrypted-vs-plaintext.
  CONFIGMAP,
  // Guards a tenant's named, multi-key SecretMap objects -- distinct from SECRET (which guards the
  // same tenant's loose flat secret keys) for the identical reason CONFIGMAP is split from CONFIG:
  // a role can be granted "read flat secrets" without also getting "read named SecretMaps."
  SECRETMAP,
  // Guards declaring/editing a tenant's LimitRange -- the per-workload min/max resource bound,
  // distinct from TENANT (which guards the tenant object itself, including its aggregate
  // ResourceQuota): a role can be granted "manage this tenant" without also getting "constrain
  // what any single deployment within it may request," the same independent-grant reasoning
  // NETWORK_POLICY already establishes relative to SERVICE.
  LIMIT_RANGE,
  // Guards triggering a chaos fault against a running instance (a node agent's own Admin Fault
  // API) -- a materially more consequential grant than an ordinary deployment operation, matching
  // the ARTIFACT/DAEMONSET/NETWORK_POLICY precedent of an independently-withholdable kind rather
  // than folding into DEPLOYMENT. Deliberately absent from every tenant role template
  // (TENANT_VIEWABLE_KINDS/TENANT_EDITABLE_KINDS/TENANT_ADMIN_ONLY_KINDS in BuiltinRoles) --
  // cluster-admin-only by default, the same posture BOOTSTRAP_TOKEN/CERTIFICATE_REQUEST already
  // take; an operator delegating it must create an explicit Role/RoleBinding.
  FAULT,
  // Guards declaring/editing a KindDefinition -- the mechanism that teaches every control-plane
  // replica a new custom resource vocabulary. Deliberately its own kind, the DAEMONSET reasoning
  // verbatim: "may teach the whole cluster a new resource kind" is a consequential grant operators
  // must be able to withhold independently; effectively platform-admin territory, absent from
  // every tenant role template.
  KIND_DEFINITION,
  // Guards instances of user-defined kinds, tenant-scoped like DEPLOYMENT. One enum value covers
  // every custom kind: per-kind granularity comes from Permission's optional qualifier (a kind
  // name for that kind's spec CRUD, "{kind}/status" for only its status writes), not from
  // widening this enum per user-defined kind -- the enum is load-bearing (BuiltinRoles, audit
  // filters, defense-in-depth re-checks in four processes) and cannot enumerate names only known
  // at runtime.
  CUSTOM_RESOURCE
}
