import type { Deployment, DeploymentInstance } from "@/types";

/**
 * Whether one placed instance is actually serving traffic, not merely occupying a replica slot --
 * the same judgment the Instances screen already renders per-row via its alive/ready status dots
 * and lifecycle badge, factored out here so the Deployments and Overview screens can reuse it
 * instead of deriving their own health verdict from placement counts alone.
 */
export function isInstanceHealthy(instance: DeploymentInstance): boolean {
  const observation = instance.observation;
  return observation.lifecycleState === "ACTIVE" && observation.alive && observation.ready;
}

/**
 * A deployment reads as healthy only when every desired replica is both placed *and* actually
 * healthy per {@link isInstanceHealthy} -- placement count alone (`instances.length ===
 * spec.replicas`) says nothing about whether a placed instance ever finished starting or has since
 * crashed, which is exactly how a deployment whose only instance is FAILED previously still showed
 * a green "healthy"/"synced" badge on the Deployments and Overview screens.
 */
export function isDeploymentHealthy(deployment: Deployment): boolean {
  if (deployment.unplacedCount > 0) return false;
  if (deployment.quotaViolating || deployment.limitRangeViolating) return false;
  if (deployment.instances.length < deployment.spec.replicas) return false;
  return deployment.instances.every(isInstanceHealthy);
}
