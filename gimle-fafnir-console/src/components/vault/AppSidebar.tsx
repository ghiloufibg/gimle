import { Link, useRouterState } from "@tanstack/react-router";
import { LayoutDashboard, KeyRound, LogOut } from "lucide-react";

import { ThemeToggle } from "@/components/theme-toggle";
import { FafnirMark } from "@/components/vault/FafnirMark";
import { Button } from "@/components/ui/button";
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
  useSidebar,
} from "@/components/ui/sidebar";
import { useAuthStore } from "@/stores/useAuthStore";

const items = [
  { title: "Overview", url: "/", icon: LayoutDashboard },
  { title: "Secrets", url: "/secrets", icon: KeyRound },
] as const;

export function AppSidebar() {
  const { state } = useSidebar();
  const collapsed = state === "collapsed";
  const pathname = useRouterState({ select: (router) => router.location.pathname });
  const principal = useAuthStore((s) => s.principal);
  const logout = useAuthStore((s) => s.logout);

  return (
    <Sidebar collapsible="icon">
      <SidebarHeader className="border-b border-sidebar-border">
        <div className="flex items-center gap-2 px-1 py-1.5">
          <FafnirMark className="size-8" />
          {!collapsed && (
            <div className="min-w-0">
              <div className="truncate text-sm font-semibold tracking-tight">
                Gimlé Fafnir Vault
              </div>
              <div className="hud-label">secrets vault</div>
            </div>
          )}
        </div>
      </SidebarHeader>

      <SidebarContent>
        <SidebarGroup>
          <SidebarGroupLabel className="hud-label">Console</SidebarGroupLabel>
          <SidebarGroupContent>
            <SidebarMenu>
              {items.map((item) => (
                <SidebarMenuItem key={item.url}>
                  <SidebarMenuButton asChild isActive={pathname === item.url} tooltip={item.title}>
                    <Link to={item.url} className="flex items-center gap-2">
                      <item.icon className="size-4" />
                      {!collapsed && <span>{item.title}</span>}
                    </Link>
                  </SidebarMenuButton>
                </SidebarMenuItem>
              ))}
            </SidebarMenu>
          </SidebarGroupContent>
        </SidebarGroup>
      </SidebarContent>

      <SidebarFooter className="border-t border-sidebar-border">
        {!collapsed && principal && (
          <div className="px-1 pb-1">
            <div className="hud-label">operator</div>
            <div className="truncate font-mono text-xs">{principal.username}</div>
          </div>
        )}
        <div className="flex items-center gap-1">
          <ThemeToggle />
          <Button
            variant="ghost"
            size="sm"
            className="h-8 flex-1 justify-start gap-2 px-2"
            onClick={() => void logout()}
          >
            <LogOut className="size-4" />
            {!collapsed && <span className="text-xs">Log out</span>}
          </Button>
        </div>
      </SidebarFooter>
    </Sidebar>
  );
}
