export function formatBytes(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(0)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(2)} MB`;
}

export function formatUptime(seconds: number): string {
  const days = Math.floor(seconds / 86_400);
  const hours = Math.floor((seconds % 86_400) / 3600);
  const minutes = Math.floor((seconds % 3600) / 60);
  return `${days}d ${hours}h ${minutes}m`;
}

export function formatAbsolute(epochMilli: number): string {
  return new Date(epochMilli).toISOString().replace("T", " ").slice(0, 19) + "Z";
}

export function formatRelative(epochMilli: number, now = Date.now()): string {
  const diff = Math.max(0, now - epochMilli);
  const minutes = Math.floor(diff / 60_000);
  if (minutes < 1) return "just now";
  if (minutes < 60) return `${minutes}m ago`;
  const hours = Math.floor(minutes / 60);
  if (hours < 24) return `${hours}h ago`;
  const days = Math.floor(hours / 24);
  if (days < 30) return `${days}d ago`;
  return `${Math.floor(days / 30)}mo ago`;
}

export function mavenCoordinates(moduleId: string): { groupId: string; artifactId: string } {
  const index = moduleId.lastIndexOf(".");
  if (index === -1) return { groupId: moduleId, artifactId: moduleId };
  return { groupId: moduleId.slice(0, index), artifactId: moduleId.slice(index + 1) };
}

export function mavenDependencySnippet(moduleId: string, version: string): string {
  const { groupId, artifactId } = mavenCoordinates(moduleId);
  return `<dependency>
  <groupId>${groupId}</groupId>
  <artifactId>${artifactId}</artifactId>
  <version>${version}</version>
</dependency>`;
}

export function mavenRepositorySnippet(baseUrl: string): string {
  return `<repository>
  <id>gimle-andvari</id>
  <name>Gimlé Andvari Registry</name>
  <url>${baseUrl}</url>
</repository>`;
}
