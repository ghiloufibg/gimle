import { Link, useNavigate, useRouterState } from "@tanstack/react-router";
import {
  LayoutDashboard,
  Boxes,
  Cpu,
  Server,
  Users,
  Network,
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

const items = [
  { title: "Overview", url: "/", icon: LayoutDashboard, exact: true },
  { title: "Metrics", url: "/metrics", icon: BarChart3 },
  { title: "Traces", url: "/traces", icon: Activity },
  { title: "Topology", url: "/topology", icon: Network },
  { title: "Deployments", url: "/deployments", icon: Boxes },
  { title: "Jobs", url: "/jobs", icon: ListChecks },
  { title: "CronJobs", url: "/cronjobs", icon: Clock },
  { title: "DaemonSets", url: "/daemonsets", icon: LayoutGrid },
  { title: "StatefulSets", url: "/statefulsets", icon: Database },
  { title: "Instances", url: "/instances", icon: Cpu },
  { title: "Nodes", url: "/nodes", icon: Server },
  { title: "Tenants", url: "/tenants", icon: Users },
  { title: "Config", url: "/config", icon: Settings },
  { title: "Secrets", url: "/secrets", icon: KeyRound },
  { title: "Access Control", url: "/access-control", icon: ShieldCheck },
  { title: "Audit", url: "/audit", icon: ScrollText },
];

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
        <SidebarGroup>
          <SidebarGroupLabel>Cluster</SidebarGroupLabel>
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
        <SidebarGroup>
          <SidebarGroupLabel>System</SidebarGroupLabel>
          <SidebarGroupContent>
            <SidebarMenu>
              <SidebarMenuItem>
                <SidebarMenuButton
                  asChild
                  isActive={pathname === "/controlplane"}
                  tooltip="Control plane"
                >
                  <Link to="/controlplane" className="flex items-center gap-2">
                    <LayoutDashboard className="h-4 w-4" />
                    <span>Control plane</span>
                  </Link>
                </SidebarMenuButton>
              </SidebarMenuItem>
            </SidebarMenu>
          </SidebarGroupContent>
        </SidebarGroup>
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
