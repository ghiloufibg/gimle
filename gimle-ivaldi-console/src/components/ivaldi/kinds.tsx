import {
  Boxes,
  Braces,
  CalendarClock,
  Cpu,
  Database,
  Gauge,
  HardDrive,
  KeyRound,
  Layers,
  Network,
  Play,
  Radio,
  Server,
  Shield,
  ShieldCheck,
  Sliders,
  Users,
  type LucideIcon,
} from "lucide-react";

import type { NodeKind } from "@/lib/blueprint";

export interface KindMeta {
  icon: LucideIcon;
  hint: string;
}

export const KIND_META: Record<NodeKind, KindMeta> = {
  machine: { icon: Server, hint: "Host that runs platform processes" },
  store: { icon: Database, hint: "Raft-backed cluster state" },
  controlPlane: { icon: Cpu, hint: "Scheduling, API and console" },
  fafnir: { icon: ShieldCheck, hint: "Secret sealing service" },
  muninn: { icon: HardDrive, hint: "Metrics and event history" },
  andvari: { icon: Boxes, hint: "Module artifact registry" },
  agent: { icon: Radio, hint: "Node agent that runs instances" },
  tenant: { icon: Users, hint: "Isolation boundary with quota" },
  deployment: { icon: Layers, hint: "Replicated stateless workload" },
  statefulSet: { icon: Database, hint: "Ordered stateful workload" },
  daemonSet: { icon: Network, hint: "One instance per agent" },
  job: { icon: Play, hint: "Run-to-completion workload" },
  cronJob: { icon: CalendarClock, hint: "Scheduled job" },
  service: { icon: Network, hint: "Stable port in front of workloads" },
  networkPolicy: { icon: Shield, hint: "Who may call whom" },
  configEntry: { icon: Braces, hint: "Tenant config key/value" },
  secret: { icon: KeyRound, hint: "Sealed value, set at run time" },
  limitRange: { icon: Sliders, hint: "Per-instance resource bounds" },
};

export const GAUGE_ICON = Gauge;
