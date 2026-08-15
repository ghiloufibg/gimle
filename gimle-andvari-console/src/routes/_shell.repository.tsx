import { createFileRoute } from "@tanstack/react-router";

import { CopyButton } from "@/components/CopyButton";
import { HudPanel } from "@/components/HudPanel";
import { PageHeader } from "@/components/PageHeader";
import { mavenRepositorySnippet } from "@/lib/format";

export const Route = createFileRoute("/_shell/repository")({
  head: () => ({
    meta: [
      { title: "Maven interop — Gimlé Andvari" },
      {
        name: "description",
        content: "Read-only Maven-2 view of the Andvari immutable artifact store.",
      },
    ],
  }),
  component: RepositoryPage,
});

function RepositoryPage() {
  const baseUrl =
    typeof window === "undefined" ? "/repository" : `${window.location.origin}/repository`;
  const snippet = mavenRepositorySnippet(baseUrl);

  return (
    <>
      <PageHeader
        eyebrow="maven interop"
        title="Repository"
        description="A Maven-2-shaped mirror of the exact same immutable store the Artifacts screen manages."
      />

      <HudPanel
        label="repository base url"
        actions={<CopyButton value={baseUrl} label="URL copied" />}
      >
        <code className="font-mono text-sm break-all">{baseUrl}</code>
      </HudPanel>

      <HudPanel
        label="pom.xml / settings.xml snippet"
        actions={<CopyButton value={snippet} label="Snippet copied" />}
      >
        <pre className="overflow-x-auto font-mono text-xs leading-relaxed">{snippet}</pre>
      </HudPanel>

      <HudPanel label="notes">
        <ul className="list-disc space-y-2 pl-5 text-sm text-muted-foreground">
          <li>
            A stock <span className="font-mono">mvn deploy</span> /{" "}
            <span className="font-mono">mvn install</span> can target this URL directly.
          </li>
          <li>
            Coordinates are derived from the module id:{" "}
            <span className="font-mono">com.example</span> +{" "}
            <span className="font-mono">greeter-provider</span> for{" "}
            <span className="font-mono">com.example.greeter-provider</span>.
          </li>
          <li>
            This page is read-only. Pushing and deleting happen through the Artifacts screen or a
            real <span className="font-mono">mvn deploy</span> — never here.
          </li>
        </ul>
      </HudPanel>
    </>
  );
}
