import { Link, useNavigate, useRouterState } from "@tanstack/react-router";
import {
  LayoutDashboard,
  Boxes,
  Cpu,
  Server,
  Users,
  Network,
  Waypoints,
  Settings,
  KeyRound,
  BarChart3,
  Activity,
  ScrollText,
  ShieldCheck,
  LogOut,
  ListChecks,
  Clock,
  LayoutGrid,
  Database,
  Package,
  FileJson,
  KeySquare,
  Puzzle,
  HardDrive,
  Gauge,
  Stamp,
} from "lucide-react";

import gimleMark from "@/assets/gimle-alt-badge.png";
import {
  Sidebar,
  SidebarContent,
  SidebarFooter,
  SidebarGroup,
  SidebarGroupContent,
  SidebarGroupLabel,
  SidebarHeader,
  SidebarMenu,
  SidebarMenuButton,
  SidebarMenuItem,
} from "@/components/ui/sidebar";
import { ThemeToggle } from "@/components/theme-toggle";
import { Button } from "@/components/ui/button";
import { useAuthStore } from "@/stores/useAuthStore";
import { groupNavEntries, type NavEntry } from "@/lib/nav";
import { collectAddonNavEntries } from "@/lib/nav-addons";

const coreItems: NavEntry[] = [
  { title: "Overview", url: "/", icon: LayoutDashboard, group: "Cluster", exact: true },
  { title: "Metrics", url: "/metrics", icon: BarChart3, group: "Cluster" },
  { title: "Traces", url: "/traces", icon: Activity, group: "Cluster" },
  { title: "Topology", url: "/topology", icon: Network, group: "Cluster" },
  { title: "Nodes", url: "/nodes", icon: Server, group: "Cluster" },
  { title: "Deployments", url: "/deployments", icon: Boxes, group: "Workloads" },
  { title: "Jobs", url: "/jobs", icon: ListChecks, group: "Workloads" },
  { title: "CronJobs", url: "/cronjobs", icon: Clock, group: "Workloads" },
  { title: "DaemonSets", url: "/daemonsets", icon: LayoutGrid, group: "Workloads" },
  { title: "StatefulSets", url: "/statefulsets", icon: Database, group: "Workloads" },
  { title: "Volumes", url: "/volumes", icon: HardDrive, group: "Workloads" },
  { title: "Instances", url: "/instances", icon: Cpu, group: "Workloads" },
  { title: "Custom Resources", url: "/custom-resources", icon: Puzzle, group: "Workloads" },
  { title: "Networking", url: "/networking", icon: Waypoints, group: "Edge" },
  { title: "Tenants", url: "/tenants", icon: Users, group: "Platform" },
  { title: "LimitRanges", url: "/limitranges", icon: Gauge, group: "Platform" },
  { title: "Config", url: "/config", icon: Settings, group: "Platform" },
  { title: "ConfigMaps", url: "/configmaps", icon: FileJson, group: "Platform" },
  { title: "Secrets", url: "/secrets", icon: KeyRound, group: "Platform" },
  { title: "SecretMaps", url: "/secretmaps", icon: KeySquare, group: "Platform" },
  { title: "Seal Keys", url: "/seal", icon: Stamp, group: "Platform" },
  { title: "Artifacts", url: "/artifacts", icon: Package, group: "Platform" },
  { title: "Access Control", url: "/access-control", icon: ShieldCheck, group: "Platform" },
  { title: "Audit", url: "/audit", icon: ScrollText, group: "Platform" },
  { title: "Control plane", url: "/controlplane", icon: LayoutDashboard, group: "System" },
];

// Addon entries are folded in in declaration order, so an addon screen is added and removed by its
// own route file alone -- nothing here names it. Resolved on first render rather than at module
// scope: the glob behind it reaches back into the route modules, one of which renders this sidebar.
let cachedNavGroups: ReturnType<typeof groupNavEntries> | null = null;

function navGroups() {
  cachedNavGroups ??= groupNavEntries([...coreItems, ...collectAddonNavEntries()]);
  return cachedNavGroups;
}

export function AppSidebar() {
  const pathname = useRouterState({ select: (r) => r.location.pathname });
  const navigate = useNavigate();
  const principal = useAuthStore((s) => s.principal);
  const logout = useAuthStore((s) => s.logout);
  const isActive = (url: string, exact?: boolean) =>
    exact ? pathname === url : pathname === url || pathname.startsWith(url + "/");

  async function handleLogout() {
    await logout();
    navigate({ to: "/login", replace: true });
  }

  return (
    <Sidebar collapsible="icon">
      <SidebarHeader>
        <div className="flex items-center gap-2 px-2 py-1.5">
          <img
            src={gimleMark}
            alt="Gimlé"
            className="h-7 w-7 rounded object-contain"
            width={28}
            height={28}
          />
          <div className="flex flex-col leading-tight group-data-[collapsible=icon]:hidden">
            <span className="text-sm font-semibold tracking-tight">Gimlé Console</span>
            <span className="text-[10px] text-muted-foreground uppercase tracking-wider">
              Cluster control plane
            </span>
          </div>
        </div>
      </SidebarHeader>
      <SidebarContent>
        {navGroups().map(({ group, items }) => (
          <SidebarGroup key={group}>
            <SidebarGroupLabel>{group}</SidebarGroupLabel>
            <SidebarGroupContent>
              <SidebarMenu>
                {items.map((item) => (
                  <SidebarMenuItem key={item.title}>
                    <SidebarMenuButton
                      asChild
                      isActive={isActive(item.url, item.exact)}
                      tooltip={item.title}
                    >
                      <Link to={item.url} className="flex items-center gap-2">
                        <item.icon className="h-4 w-4" />
                        <span>{item.title}</span>
                      </Link>
                    </SidebarMenuButton>
                  </SidebarMenuItem>
                ))}
              </SidebarMenu>
            </SidebarGroupContent>
          </SidebarGroup>
        ))}
      </SidebarContent>
      <SidebarFooter>
        <div className="flex items-center justify-between px-1 group-data-[collapsible=icon]:justify-center">
          <span className="text-[10px] text-muted-foreground font-mono group-data-[collapsible=icon]:hidden">
            {principal ? principal.username : `v${__APP_VERSION__}`}
          </span>
          <div className="flex items-center gap-1 group-data-[collapsible=icon]:hidden">
            <ThemeToggle />
            <Button
              variant="ghost"
              size="sm"
              onClick={handleLogout}
              aria-label="Log out"
              className="gap-2"
            >
              <LogOut className="h-4 w-4" />
              <span className="text-xs">Log out</span>
            </Button>
          </div>
        </div>
      </SidebarFooter>
    </Sidebar>
  );
}
