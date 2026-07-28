export type LifecycleState =
  | "INSTALLED"
  | "RESOLVED"
  | "STARTING"
  | "ACTIVE"
  | "STOPPING"
  | "UNINSTALLED";

export type Tier = "TIER_1" | "TIER_2" | "TIER_3";

export interface ModuleId {
  name: string;
  version: string;
}

export interface InstanceObservation {
  lifecycleState: LifecycleState;
  alive: boolean;
  ready: boolean;
  requestRatePerSecond: number;
  queueDepth: number;
  cpuMillicoresUsed: number;
  memoryBytesUsed: number;
}

export interface DeploymentInstance {
  instanceIndex: number;
  nodeId: string;
  observation: InstanceObservation;
}

export interface DeploymentSpec {
  name: string;
  moduleId: ModuleId;
  artifactPath: string;
  replicas: number;
  tenantId: string | null;
}

export interface DeploymentSpecInput {
  name: string;
  moduleId: ModuleId;
  artifactPath: string;
  replicas: number;
  tenantId: string | null;
}

export interface Deployment {
  spec: DeploymentSpec;
  instances: DeploymentInstance[];
  unplacedCount: number;
  quotaViolating: boolean;
}

export interface Node {
  nodeId: string;
  capabilities: { supportedTiers: Tier[] };
  lastHeartbeatAt: string | null;
  capacity: {
    totalMemoryBytes: number;
    assignedMemoryBytes: number;
    totalCpuMillicores: number;
    assignedCpuMillicores: number;
  };
}

export interface Tenant {
  id: string;
  quota: {
    maxMemoryBytes: number;
    maxCpuMillicores: number;
    maxInstances: number;
  };
}

export interface ConfigEntry {
  tenantId: string;
  key: string;
  value: string;
  encrypted: boolean;
}

export interface ModuleInstance {
  deploymentName: string;
  instanceIndex: number;
  moduleId: ModuleId;
  artifactPath: string;
  tenantId: string | null;
  nodeId: string;
  lifecycleState: LifecycleState;
  alive: boolean;
  ready: boolean;
  requestRatePerSecond: number;
  queueDepth: number;
  cpuMillicoresUsed: number;
  memoryBytesUsed: number;
}

export type LogLevel = "TRACE" | "DEBUG" | "INFO" | "WARN" | "ERROR";
export type LogCategory = "APPLICATION" | "PLATFORM" | "SYSTEM";
export type ProcessRole = "WORKER" | "CONTROLLER" | "NODE";

export interface StructuredLogLine {
  timestamp: string;
  level: LogLevel;
  logger: string;
  thread: string;
  message: string;
  category: LogCategory;
  processRole: ProcessRole;
  nodeId: string;
  moduleId?: string;
  moduleVersion?: string;
  deploymentName?: string;
  instanceIndex?: number;
  tenantId?: string;
}

export interface RawLogLine {
  timestamp: string;
  category: "SYSTEM";
  raw: string;
}

export type LogLine = StructuredLogLine | RawLogLine;

export type LogTarget =
  | {
      kind: "instance";
      deploymentName: string;
      instanceIndex: number;
      category: "APPLICATION" | "PLATFORM";
    }
  | { kind: "node"; nodeId: string; category: "PLATFORM" | "SYSTEM" }
  | { kind: "controlplane"; category: "PLATFORM" | "SYSTEM" };

export interface Page<T> {
  items: T[];
  nextCursor: string | null;
}
