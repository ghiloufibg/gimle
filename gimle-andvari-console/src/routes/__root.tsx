import type { QueryClient } from "@tanstack/react-query";
import { QueryClientProvider } from "@tanstack/react-query";
import {
  HeadContent,
  Link,
  Outlet,
  createRootRouteWithContext,
  useRouter,
} from "@tanstack/react-router";

import { AndvariMark } from "@/components/AndvariMark";
import { Button } from "@/components/ui/button";
import { Toaster } from "@/components/ui/sonner";
import { TooltipProvider } from "@/components/ui/tooltip";

export const Route = createRootRouteWithContext<{ queryClient: QueryClient }>()({
  head: () => ({
    meta: [
      { title: "Gimlé Andvari Registry" },
      {
        name: "description",
        content: "Operator console for the Gimlé Andvari module artifact registry.",
      },
      { name: "robots", content: "noindex" },
    ],
  }),
  component: RootComponent,
  notFoundComponent: NotFound,
  errorComponent: ErrorScreen,
});

function RootComponent() {
  const { queryClient } = Route.useRouteContext();
  return (
    <QueryClientProvider client={queryClient}>
      <TooltipProvider delayDuration={200}>
        <HeadContent />
        <Outlet />
        <Toaster position="bottom-right" />
      </TooltipProvider>
    </QueryClientProvider>
  );
}

function CenteredCard({
  title,
  message,
  children,
}: {
  title: string;
  message: string;
  children: React.ReactNode;
}) {
  return (
    <div className="flex min-h-screen items-center justify-center bg-background px-4">
      <div className="hud-panel w-full max-w-sm rounded-sm p-8 text-center">
        <AndvariMark className="mx-auto h-14 w-14" />
        <p className="hud-label mt-4">gimle // andvari</p>
        <h1 className="mt-2 text-lg font-semibold">{title}</h1>
        <p className="mt-2 text-sm text-muted-foreground">{message}</p>
        <div className="mt-6 flex justify-center">{children}</div>
      </div>
    </div>
  );
}

function NotFound() {
  return (
    <CenteredCard title="404 — no such route" message="This console page does not exist.">
      <Button asChild size="sm" className="rounded-sm">
        <Link to="/">Back to overview</Link>
      </Button>
    </CenteredCard>
  );
}

function ErrorScreen({ error }: { error: Error }) {
  const router = useRouter();
  return (
    <CenteredCard
      title="Something went wrong"
      message={error.message || "The console hit an unexpected error."}
    >
      <Button size="sm" className="rounded-sm" onClick={() => void router.invalidate()}>
        Retry
      </Button>
    </CenteredCard>
  );
}
